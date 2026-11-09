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
    private val api: SupabaseApi,
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

    private suspend fun dispatch(entry: OutboxEntity): Outcome<Unit> = when (entry.kind) {
        // Client-generated ids make every write idempotent: replaying an operation that already
        // reached the server must not surface a false "already exists" conflict to the user.
        "post_insert" -> api.insert("posts", entry.payload, upsert = true, onConflict = "id").toUnit()
        "post_delete" -> api.delete("posts", listOf("id" to "eq.${idFromPayload(entry.payload)}")).toUnit()
        "comment_insert" -> api.insert("post_comments", entry.payload, upsert = true, onConflict = "id").toUnit()
        "reaction_add" -> api.insert("post_reactions", entry.payload, ignoreDuplicates = true).toUnit()
        "reaction_remove" -> api.delete(
            "post_reactions",
            listOf(
                "post_id" to "eq.${fieldFromPayload(entry.payload, "post_id")}",
                "user_id" to "eq.${fieldFromPayload(entry.payload, "user_id")}",
            ),
        ).toUnit()
        "profile_upsert" -> api.insert("profiles", entry.payload, upsert = true, onConflict = "id").toUnit()
        "community_create" -> api.insert("communities", entry.payload, upsert = true, onConflict = "id").toUnit()
        "community_join" -> api.insert("community_members", entry.payload, upsert = true).toUnit()
        "conversation_create" -> api.insert(
            "conversations",
            entry.payload,
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
        "story_insert" -> api.insert("stories", entry.payload, representation = false).toUnit()
        "story_view" -> api.insert(
            "story_views",
            entry.payload,
            ignoreDuplicates = true,
            onConflict = "story_id,viewer_id",
            representation = false,
        ).toUnit()
        "story_delete" -> api.delete("stories", listOf("id" to "eq.${idFromPayload(entry.payload)}")).toUnit()
        "message_send" -> api.insert("messages", entry.payload, upsert = true, onConflict = "id").toUnit()
        "report_create" -> api.insert("reports", entry.payload, upsert = true, onConflict = "id").toUnit()
        "block_create" -> api.insert("blocks", entry.payload, upsert = true).toUnit()
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

    private fun <T> Outcome<T>.toUnit(): Outcome<Unit> = when (this) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> this
    }

    private fun fieldFromPayload(payload: String, field: String): String =
        Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(payload)?.groupValues?.get(1) ?: ""

    private fun idFromPayload(payload: String): String = fieldFromPayload(payload, "id")
}
