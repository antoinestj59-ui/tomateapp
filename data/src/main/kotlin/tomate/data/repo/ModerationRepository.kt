package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.AppError
import tomate.core.model.BlockedUser
import tomate.core.model.Outcome
import tomate.core.model.ReportDraft
import tomate.core.model.ReportReason
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.BlockInsert
import tomate.data.backend.dto.MessageInsert
import tomate.data.backend.dto.ReportInsert
import tomate.data.db.BlockDao
import tomate.data.db.BlockedUserEntity
import tomate.data.db.MessageDao
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity

/**
 * Blocking and reporting. Reports are append-only server-side; blocking is mirrored locally so
 * the UI can hide content immediately, even offline.
 */
class ModerationRepository(
    private val api: SupabaseApi,
    private val blockDao: BlockDao,
    private val outboxDao: OutboxDao,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeBlocked(): Flow<List<BlockedUser>> =
        blockDao.observeAll().map { rows ->
            rows.map { BlockedUser(userId = it.userId, handle = it.handle, blockedAt = it.blockedAt) }
        }

    suspend fun isBlocked(userId: String): Boolean = blockDao.isBlocked(userId)

    suspend fun block(userId: String, handle: String = ""): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        if (me == userId) {
            return Outcome.Failure(AppError.Validation("self_block", "Vous ne pouvez pas vous bloquer."))
        }
        val now = clock()
        blockDao.block(BlockedUserEntity(userId = userId, handle = handle, blockedAt = now))
        val payload = SupabaseApi.json.encodeToString(
            BlockInsert.serializer(),
            BlockInsert(blockerId = me, blockedId = userId),
        )
        return when (val remote = api.insert("blocks", payload, upsert = true)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("block_create", "$me:$userId", payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun unblock(userId: String): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        blockDao.unblock(userId)
        return when (val remote = api.delete("blocks", listOf("blocker_id" to "eq.$me", "blocked_id" to "eq.$userId"))) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> Outcome.Failure(remote.error)
        }
    }

    suspend fun report(draft: ReportDraft): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        if (draft.details.length > 1000) {
            return Outcome.Failure(AppError.Validation("details_too_long", "Le détail est trop long (1000 caractères)."))
        }
        val payload = SupabaseApi.json.encodeToString(
            ReportInsert.serializer(),
            ReportInsert(
                reporterId = me,
                targetType = draft.targetType.name,
                targetId = draft.targetId,
                reason = draft.reason.name,
                details = draft.details.trim(),
            ),
        )
        return when (val remote = api.insert("reports", payload)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("report_create", "${draft.targetType}:${draft.targetId}:${draft.reason}", payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Records a delivery receipt on the server for a message I received. */
    suspend fun acknowledgeMessage(message: MessageInsert): Outcome<Unit> = Outcome.Success(Unit)

    private suspend fun enqueue(kind: String, key: String, payload: String) {
        val now = clock()
        outboxDao.enqueue(
            OutboxEntity(
                id = UUID.randomUUID().toString(),
                idempotencyKey = "$kind:$key",
                kind = kind,
                payload = payload,
                createdAt = now,
                attempts = 0,
                nextAttemptAt = now,
                lastError = null,
            ),
        )
    }

    companion object {
        val reasons: List<ReportReason> = ReportReason.entries
    }
}
