package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.core.model.Story
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.StoryInsert
import tomate.data.backend.dto.StoryRowDto
import tomate.data.backend.dto.StoryViewInsert
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity
import tomate.data.db.ProfileDao
import tomate.data.db.StoryDao
import tomate.data.db.StoryEntity
import tomate.data.mapper.toDomain
import tomate.data.mapper.toEntity

/**
 * 24-hour stories, local-first like the rest of TOMATE.
 *
 * The expiry is enforced twice: the server refuses to select an expired row and the local query
 * only returns `expiresAt > now`, so a story written offline still disappears on time without
 * any network round-trip.
 */
class StoryRepository(
    private val api: SupabaseApi,
    private val storyDao: StoryDao,
    private val profileDao: ProfileDao,
    private val outboxDao: OutboxDao,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeStories(now: Long): Flow<List<Story>> =
        storyDao.observeActive(now).map { rows -> rows.map { it.toDomain() } }

    /** Pulls visible, non-expired stories from the server. */
    suspend fun refresh(): Outcome<Int> {
        if (session.isLocalAccount()) return Outcome.Success(0)
        val query = listOf(
            "select" to "*",
            "order" to "created_at.asc",
            "limit" to "120",
        )
        return when (val response = api.select("stories_feed", query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(
                        ListSerializer(StoryRowDto.serializer()),
                        response.value,
                    )
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Stories illisibles")) }
                val existing = storyDao.all().associateBy { it.id }
                val merged = rows.map { dto ->
                    val entity = dto.toEntity()
                    // A view queued offline must not be undone by a stale server row.
                    if (existing[entity.id]?.viewedByMe == true) entity.copy(viewedByMe = true) else entity
                }
                storyDao.upsertAll(merged)
                storyDao.deleteExpired(clock())
                Outcome.Success(merged.size)
            }
        }
    }

    suspend fun create(body: String, background: String): Outcome<Story> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("empty_story", "Votre story est vide."))
        }
        if (trimmed.length > MAX_STORY_LENGTH) {
            return Outcome.Failure(
                AppError.Validation("story_too_long", "Maximum $MAX_STORY_LENGTH caractères."),
            )
        }
        val palette = background.takeIf { it in BACKGROUNDS } ?: BACKGROUNDS.first()
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val profile = profileDao.byId(userId)
        val now = clock()
        val storyId = UUID.randomUUID().toString()
        val entity = StoryEntity(
            id = storyId,
            authorId = userId,
            authorHandle = profile?.handle ?: "",
            authorDisplayName = profile?.displayName ?: "",
            body = trimmed,
            background = palette,
            createdAt = now,
            expiresAt = now + TTL_MS,
            viewedByMe = true,
            pendingSync = true,
        )
        storyDao.upsert(entity)

        val payload = SupabaseApi.json.encodeToString(
            StoryInsert.serializer(),
            StoryInsert(id = storyId, authorId = userId, body = trimmed, background = palette),
        )
        if (SessionManager.isLocalId(userId)) {
            enqueue("story_insert", storyId, payload)
            return Outcome.Success(entity.toDomain())
        }
        return when (val remote = api.insert("stories", payload, representation = false)) {
            is Outcome.Success -> {
                storyDao.markSynced(storyId)
                Outcome.Success(entity.copy(pendingSync = false).toDomain())
            }
            is Outcome.Failure -> {
                enqueue("story_insert", storyId, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Records a view once; the local flag is set immediately so the ring updates offline. */
    suspend fun markViewed(storyId: String): Outcome<Unit> {
        val story = storyDao.byId(storyId) ?: return Outcome.Failure(AppError.NotFound())
        if (story.viewedByMe) return Outcome.Success(Unit)
        storyDao.markViewed(storyId)
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Success(Unit)
        val payload = SupabaseApi.json.encodeToString(
            StoryViewInsert.serializer(),
            StoryViewInsert(storyId = storyId, viewerId = userId),
        )
        if (SessionManager.isLocalId(userId)) {
            enqueue("story_view", "$storyId:$userId", payload)
            return Outcome.Success(Unit)
        }
        return when (
            val remote = api.insert(
                "story_views",
                payload,
                ignoreDuplicates = true,
                onConflict = "story_id,viewer_id",
                representation = false,
            )
        ) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("story_view", "$storyId:$userId", payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun delete(storyId: String): Outcome<Unit> {
        val story = storyDao.byId(storyId) ?: return Outcome.Success(Unit)
        storyDao.deleteById(storyId)
        if (SessionManager.isLocalId(story.authorId)) {
            enqueue("story_delete", storyId, """{"id":"$storyId"}""")
            return Outcome.Success(Unit)
        }
        return when (val remote = api.delete("stories", listOf("id" to "eq.$storyId"))) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("story_delete", storyId, """{"id":"$storyId"}""")
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Server timestamp of the expiry, for a story that exists locally only. */
    fun localExpiry(createdAt: Long): Long = createdAt + TTL_MS

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
        const val MAX_STORY_LENGTH = 300
        const val TTL_MS = 24L * 60 * 60 * 1000
        val BACKGROUNDS = listOf("sunset", "ocean", "forest", "night", "plain")
    }
}
