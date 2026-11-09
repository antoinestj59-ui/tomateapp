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
import tomate.data.backend.SupabaseConfig
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

    /**
     * Creates a story, optionally with a photo or a video.
     *
     * The media is uploaded before the row is written: publishing a row that points to a missing
     * object would show a broken story to everyone. When the upload fails nothing is queued,
     * because the bytes cannot be replayed from the outbox later.
     */
    suspend fun create(
        body: String,
        background: String,
        media: ByteArray? = null,
        mediaType: String? = null,
        mimeType: String? = null,
        extension: String = "jpg",
    ): Outcome<Story> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && media == null) {
            return Outcome.Failure(AppError.Validation("empty_story", "Votre story est vide."))
        }
        if (media != null && mediaType !in setOf("image", "video")) {
            return Outcome.Failure(AppError.Validation("story_media", "Média inconnu."))
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
        var mediaPath: String? = null
        if (media != null) {
            if (SessionManager.isLocalId(userId)) {
                return Outcome.Failure(
                    AppError.Offline("Photo et vidéo : connexion requise"),
                )
            }
            val path = "$userId/$storyId.$extension"
            when (val uploaded = api.uploadObject("stories", path, media, mimeType ?: "image/jpeg")) {
                is Outcome.Failure -> return Outcome.Failure(
                    AppError.Unknown("Envoi du média impossible : vérifiez la connexion."),
                )
                is Outcome.Success -> mediaPath = path
            }
        }
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
            mediaPath = mediaPath,
            mediaType = if (mediaPath != null) mediaType else null,
        )
        storyDao.upsert(entity)

        val payload = SupabaseApi.json.encodeToString(
            StoryInsert.serializer(),
            StoryInsert(
                id = storyId,
                authorId = userId,
                body = trimmed,
                background = palette,
                mediaPath = mediaPath,
                mediaType = if (mediaPath != null) mediaType else null,
            ),
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

    /** Public URL of a story medium, or null when the story is text-only. */
    fun mediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val encoded = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "${SupabaseConfig.url}/storage/v1/object/public/stories/$encoded"
    }

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
