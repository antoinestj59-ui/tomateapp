package tomate.data.account

import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.ProfileDto
import tomate.data.db.CommentDao
import tomate.data.db.CommunityDao
import tomate.data.db.ConversationDao
import tomate.data.db.MessageDao
import tomate.data.db.OutboxDao
import tomate.data.db.PostDao
import tomate.data.db.ProfileDao
import tomate.data.db.ProfileEntity

/**
 * Bridges a purely local identity to a real Supabase anonymous account.
 *
 * Why this exists: TOMATE must let a user create an account with no connection at all
 * (docs/01). Such an account gets a local id (`local-<uuid>`) so every local write keeps
 * working. As soon as the device is online again, this class:
 *
 *  1. creates a real anonymous Supabase session;
 *  2. rewrites the local id to the server id in one pass (profile, posts, comments, messages,
 *     conversation members, community ownership, pending outbox payloads);
 *  3. pushes the profile so the account exists server-side before queued rows reference it.
 *
 * The operation is idempotent: if it is interrupted after the rewrite, the next call only
 * retries the profile push. Nothing is deleted, and no local content is lost.
 */
class AccountLinker(
    private val api: SupabaseApi,
    private val session: SessionManager,
    private val profileDao: ProfileDao,
    private val postDao: PostDao,
    private val commentDao: CommentDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val communityDao: CommunityDao,
    private val outboxDao: OutboxDao,
) {
    data class LinkReport(
        val linked: Boolean,
        val userId: String,
        val rewrittenRows: Int = 0,
    )

    suspend fun isLocalAccount(): Boolean = session.isLocalAccount()

    /**
     * Links the local account when one exists. Returns [LinkReport.linked] = true only when the
     * local identity was actually migrated during this call.
     */
    suspend fun linkIfNeeded(): Outcome<LinkReport> {
        val me = profileDao.me()
            ?: return Outcome.Failure(AppError.NotFound("Aucun profil local à relier"))

        if (!SessionManager.isLocalId(me.id)) {
            // A previous run may have been interrupted between the rewrite and the push.
            return if (me.pendingSync) pushProfile(me).let { pushed ->
                when (pushed) {
                    is Outcome.Success -> {
                        profileDao.markSynced(me.id)
                        Outcome.Success(LinkReport(linked = false, userId = me.id))
                    }
                    is Outcome.Failure -> pushed
                }
            } else {
                Outcome.Success(LinkReport(linked = false, userId = me.id))
            }
        }

        val oldId = me.id
        val remote = when (val result = session.ensureRemoteSession()) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return result
        }
        val newId = remote.userId

        // One pass, no partial window: every table that stores the actor id is rewritten.
        profileDao.rekey(oldId, newId)
        postDao.reassignAuthor(oldId, newId)
        commentDao.reassignAuthor(oldId, newId)
        messageDao.reassignSender(oldId, newId)
        conversationDao.reassignMember(oldId, newId)
        communityDao.reassignOwner(oldId, newId)
        communityDao.reassignMemberCache(oldId, newId)
        outboxDao.rekeyPayload(oldId, newId)

        val rewritten = me.copy(id = newId, pendingSync = true)
        return when (val pushed = pushProfile(rewritten)) {
            is Outcome.Success -> {
                profileDao.markSynced(newId)
                Outcome.Success(LinkReport(linked = true, userId = newId))
            }
            is Outcome.Failure -> pushed
        }
    }

    private suspend fun pushProfile(profile: ProfileEntity): Outcome<String> {
        val dto = ProfileDto(
            id = profile.id,
            handle = profile.handle,
            displayName = profile.displayName,
            bio = profile.bio,
            visibility = profile.visibility.name,
        )
        val body = SupabaseApi.json.encodeToString(ProfileDto.serializer(), dto)
        return api.insert("profiles", body, upsert = true, onConflict = "id")
    }
}
