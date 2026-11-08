package tomate.data.sync

import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.SupabaseApi
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
) {
    data class SyncReport(
        val attempted: Int,
        val succeeded: Int,
        val rescheduled: Int,
        val dropped: Int,
        val lastNameError: String? = null,
    )

    suspend fun drain(maxEntries: Int = 25): SyncReport {
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
        "post_insert" -> api.insert("posts", entry.payload).toUnit()
        "post_delete" -> api.delete("posts", listOf("id" to "eq.${idFromPayload(entry.payload)}")).toUnit()
        "comment_insert" -> api.insert("post_comments", entry.payload).toUnit()
        "reaction_add" -> api.insert("post_reactions", entry.payload, upsert = true).toUnit()
        "reaction_remove" -> api.delete(
            "post_reactions",
            listOf(
                "post_id" to "eq.${fieldFromPayload(entry.payload, "post_id")}",
                "user_id" to "eq.${fieldFromPayload(entry.payload, "user_id")}",
            ),
        ).toUnit()
        "profile_upsert" -> api.insert("profiles", entry.payload, upsert = true, onConflict = "id").toUnit()
        "community_create" -> api.insert("communities", entry.payload).toUnit()
        "community_join" -> api.insert("community_members", entry.payload, upsert = true).toUnit()
        "conversation_create" -> api.insert("conversations", entry.payload).toUnit()
        "message_send" -> api.insert("messages", entry.payload).toUnit()
        "report_create" -> api.insert("reports", entry.payload).toUnit()
        "block_create" -> api.insert("blocks", entry.payload, upsert = true).toUnit()
        else -> Outcome.Failure(
            AppError.Validation(
                technicalMessage = "unknown_outbox_kind:${entry.kind}",
                userMessage = "Opération inconnue en attente.",
            ),
        )
    }

    private fun <T> Outcome<T>.toUnit(): Outcome<Unit> = when (this) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> this
    }

    private fun fieldFromPayload(payload: String, field: String): String =
        Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(payload)?.groupValues?.get(1) ?: ""

    private fun idFromPayload(payload: String): String = fieldFromPayload(payload, "id")
}
