package tomate.data.sync

import tomate.core.model.AppError
import tomate.core.model.Outcome
import kotlinx.serialization.builtins.ListSerializer
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.ProfileDto
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity

/**
 * Drains the durable outbox.
 *
 * Rules:
 *  - every entry has an idempotency key, so a replay cannot duplicate a post or a message;
 *  - retryable failures are rescheduled with exponential backoff + jitter;
 *  - non-retryable failures (validation, conflict, not found) are dropped after being logged in
 *    `lastError`, which prevents an infinite retry loop on a permanently invalid payload;
 *  - the loop is bounded per call so a huge backlog cannot hang the app.
 */
class SyncEngine(
    private val api: OutboxRemote,
    private val outboxDao: OutboxDao,
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Gate: a local account has no server reach yet, queued work must stay untouched. */
    private val canSync: suspend () -> Boolean = { true },
) {
    data class SyncReport(
        val attempted: Int,
        val succeeded: Int,
        val rescheduled: Int,
        val dropped: Int,
        val lastNameError: String? = null,
    )

    suspend fun drain(maxEntries: Int = 25): SyncReport {
        if (!canSync()) {
            return SyncReport(attempted = 0, succeeded = 0, rescheduled = 0, dropped = 0)
        }
        val now = clock()
        val due = outboxDao.due(now, maxEntries)
        var succeeded = 0
        var rescheduled = 0
        var dropped = 0
        var lastError: String? = null

        due.forEach { entry ->
            when (val result = dispatch(entry)) {
                is Outcome.Success -> {
                    outboxDao.done(entry.id)
                    succeeded++
                }
                is Outcome.Failure -> {
                    lastError = result.error.technicalMessage
                    val attempts = entry.attempts + 1
                    if (result.error.isRetryable && backoff.shouldRetry(attempts)) {
                        outboxDao.reschedule(
                            id = entry.id,
                            nextAttemptAt = clock() + backoff.delayForAttempt(attempts),
                            error = result.error.technicalMessage,
                        )
                        rescheduled++
                    } else {
                        outboxDao.reschedule(
                            id = entry.id,
                            nextAttemptAt = Long.MAX_VALUE,
                            error = result.error.technicalMessage,
                        )
                        dropped++
                    }
                }
            }
        }
        return SyncReport(
            attempted = due.size,
            succeeded = succeeded,
            rescheduled = rescheduled,
            dropped = dropped,
            lastNameError = lastError,
        )
    }

    /**
     * Toutes les écritures sont volontairement insensibles au rejeu.
     *
     * Un rejeu se produisait pour de vrai : la requête atteignait le serveur, puis la réponse se
     * perdait (réseau coupé, app tuée). La deuxième tentative tombait alors sur une violation
     * d'unicité (23505) que l'app affichait en « conflit de données », et l'opération restait
     * bloquée à vie. `ignore-duplicates` avec la clé d'idempotence de la table fait exactement
     * ce qu'il faut : si la ligne est là, on ne fait rien.
     */
    /** Nombre d'opérations encore en attente d'un envoi réussi. */
    suspend fun pendingCount(): Int = outboxDao.count()

    /**
     * Remet en file les opérations refusées, puis retente immédiatement. Retourne le nombre
     * d'opérations remises en jeu.
     */
    suspend fun retryFailed(): Int {
        val revived = outboxDao.reviveDead()
        if (revived > 0) drain()
        return revived
    }

    /** Abandonne les opérations refusées : elles ne repartiront plus. Retourne le nombre effacé. */
    suspend fun discardFailed(): Int = outboxDao.discardDead()

    private suspend fun dispatch(entry: OutboxEntity): Outcome<Unit> = when (entry.kind) {
        "post_insert" -> api.insert(
            "posts", entry.payload, ignoreDuplicates = true, onConflict = "id",
        ).toUnit()
        "post_update" -> api.update("posts", listOf("id" to "eq.${idFromPayload(entry.payload)}"), entry.payload).toUnit()
        "post_delete" -> api.delete("posts", listOf("id" to "eq.${idFromPayload(entry.payload)}")).toUnit()
        "comment_insert" -> api.insert(
            "post_comments", entry.payload, ignoreDuplicates = true, onConflict = "id",
        ).toUnit()
        "reaction_add" -> api.insert(
            "post_reactions", entry.payload, ignoreDuplicates = true,
            onConflict = "post_id,user_id,kind",
        ).toUnit()
        "reaction_remove" -> api.delete(
            "post_reactions",
            listOf(
                "post_id" to "eq.${fieldFromPayload(entry.payload, "post_id")}",
                "user_id" to "eq.${fieldFromPayload(entry.payload, "user_id")}",
            ),
        ).toUnit()
        // Une participation d'événement est une réaction de kind `participe` : la suppression
        // filtre sur ce kind pour ne pas effacer aussi la réaction « tomate » du même compte.
        "participation_add" -> api.insert(
            "post_reactions", entry.payload, ignoreDuplicates = true,
            onConflict = "post_id,user_id,kind",
        ).toUnit()
        "participation_remove" -> api.delete(
            "post_reactions",
            listOf(
                "post_id" to "eq.${fieldFromPayload(entry.payload, "post_id")}",
                "user_id" to "eq.${fieldFromPayload(entry.payload, "user_id")}",
                "kind" to "eq.participe",
            ),
        ).toUnit()
        "profile_upsert" -> api.insert("profiles", entry.payload, upsert = true, onConflict = "id").toUnit()
        "community_create" -> api.insert(
            "communities", entry.payload, ignoreDuplicates = true, onConflict = "id",
        ).toUnit()
        // La clé primaire est (community_id, user_id) : ignorer le doublon est le seul comportement
        // possible, car un ON CONFLICT DO UPDATE exigerait une politique UPDATE que le membre
        // n'a pas sur sa propre ligne.
        "community_join" -> api.insert(
            "community_members", entry.payload, ignoreDuplicates = true,
            onConflict = "community_id,user_id",
        ).toUnit()
        "conversation_create" -> api.insert(
            "conversations",
            entry.payload,
            ignoreDuplicates = true,
            onConflict = "id",
            representation = false,
        ).toUnit()
        "conversation_member_add" -> api.insert(
            "conversation_members",
            entry.payload,
            ignoreDuplicates = true,
            onConflict = "conversation_id,user_id",
            representation = false,
        ).toUnit()
        "conversation_member_add_handle" -> addMemberByHandle(entry.payload)
        "story_insert" -> api.insert(
            "stories", entry.payload, ignoreDuplicates = true, onConflict = "id",
            representation = false,
        ).toUnit()
        "story_view" -> api.insert(
            "story_views",
            entry.payload,
            ignoreDuplicates = true,
            onConflict = "story_id,viewer_id",
            representation = false,
        ).toUnit()
        "story_delete" -> api.delete("stories", listOf("id" to "eq.${idFromPayload(entry.payload)}")).toUnit()
        "message_send" -> api.insert(
            "messages", entry.payload, ignoreDuplicates = true, onConflict = "id",
        ).toUnit()
        "conversation_rename" -> renameConversation(entry.payload).toUnit()
        "message_reaction_add" -> api.insert(
            "message_reactions", entry.payload, ignoreDuplicates = true,
            onConflict = "message_id,user_id,emoji",
        ).toUnit()
        "message_reaction_remove" -> api.delete(
            "message_reactions",
            listOf(
                "message_id" to "eq.${fieldFromPayload(entry.payload, "message_id")}",
                "user_id" to "eq.${fieldFromPayload(entry.payload, "user_id")}",
                "emoji" to "eq.${fieldFromPayload(entry.payload, "emoji")}",
            ),
        ).toUnit()
        "report_create" -> api.insert(
            "reports", entry.payload, ignoreDuplicates = true, onConflict = "id",
        ).toUnit()
        "block_create" -> api.insert(
            "blocks", entry.payload, ignoreDuplicates = true,
            onConflict = "blocker_id,blocked_id",
        ).toUnit()
        else -> Outcome.Failure(
            AppError.Validation(
                technicalMessage = "unknown_outbox_kind:${entry.kind}",
                userMessage = "Opération inconnue en attente.",
            ),
        )
    }

    /** Resolves a handle at delivery time: an offline conversation still reaches its peer. */
    private suspend fun addMemberByHandle(payload: String): Outcome<Unit> {
        val conversationId = fieldFromPayload(payload, "conversation_id")
        val handle = fieldFromPayload(payload, "handle")
        if (conversationId.isBlank() || handle.isBlank()) {
            return Outcome.Failure(AppError.Validation("member_payload", "Membre de conversation incomplet."))
        }
        return when (val response = api.select("profiles", listOf("select" to "id", "handle" to "eq.$handle"))) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val userId = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(ProfileDto.serializer()), response.value)
                        .firstOrNull()?.id
                }.getOrNull()
                    ?: return Outcome.Failure(AppError.NotFound("Destinataire introuvable : @$handle"))
                api.insert(
                    "conversation_members",
                    """{"conversation_id":"$conversationId","user_id":"$userId"}""",
                    ignoreDuplicates = true,
                    onConflict = "conversation_id,user_id",
                    representation = false,
                ).toUnit()
            }
        }
    }

    private suspend fun renameConversation(payload: String): Outcome<String> {
        val element = runCatching { SupabaseApi.json.parseToJsonElement(payload) }.getOrNull()
        val obj = element as? kotlinx.serialization.json.JsonObject
            ?: return Outcome.Failure(AppError.Validation("member_payload", "Conversation à renommer incomplète."))
        val conversationId = obj["conversation_id"]?.toString()?.trim('"').orEmpty()
        val title = obj["title"]?.toString()?.trim('"').orEmpty()
        if (conversationId.isBlank() || title.isBlank()) {
            return Outcome.Failure(AppError.Validation("member_payload", "Conversation à renommer incomplète."))
        }
        val body = SupabaseApi.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(title)) },
        )
        return api.update("conversations", listOf("id" to "eq.$conversationId"), body)
    }

    private fun <T> Outcome<T>.toUnit(): Outcome<Unit> = when (this) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> this
    }

    private fun fieldFromPayload(payload: String, field: String): String =
        Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(payload)?.groupValues?.get(1) ?: ""

    private fun idFromPayload(payload: String): String = fieldFromPayload(payload, "id")
}
