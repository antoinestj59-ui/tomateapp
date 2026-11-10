package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tomate.core.model.AppError
import tomate.core.model.Comment
import tomate.core.model.Outcome
import tomate.core.model.Post
import tomate.core.model.Visibility
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.CommentDto
import tomate.data.backend.dto.CommentInsert
import tomate.data.backend.dto.FeedRowDto
import tomate.data.backend.dto.PostInsert
import tomate.data.backend.dto.ReactionInsert
import tomate.data.db.CommentDao
import tomate.data.db.CommentEntity
import tomate.data.db.OutboxEntity
import tomate.data.db.OutboxDao
import tomate.data.db.PostDao
import tomate.data.db.PostEntity
import tomate.data.db.ProfileDao
import tomate.data.mapper.toDomain
import tomate.data.mapper.toEntity

/**
 * Social feed. Everything is written to Room first, so the timeline is readable offline and
 * publications survive a lost connection (they are retried from the outbox).
 */
class FeedRepository(
    private val api: SupabaseApi,
    private val postDao: PostDao,
    private val commentDao: CommentDao,
    private val profileDao: ProfileDao,
    private val outboxDao: OutboxDao,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeFeed(communityId: String? = null, limit: Int = 60): Flow<List<Post>> =
        postDao.observeFeed(communityId, limit, 0).map { rows -> rows.map { it.toDomain() } }

    fun observePost(id: String): Flow<Post?> = postDao.observeById(id).map { it?.toDomain() }

    fun observeComments(postId: String): Flow<List<Comment>> =
        commentDao.observeForPost(postId).map { rows -> rows.map { it.toDomain() } }

    fun observeAuthorPosts(authorId: String): Flow<List<Post>> =
        postDao.observeByAuthor(authorId).map { rows -> rows.map { it.toDomain() } }

    /** Pulls the server feed (`feed` view: post + author + counters) into Room. */
    suspend fun refreshFeed(communityId: String? = null): Outcome<Int> {
        // A local account has no server reach yet: the cached feed is the honest answer.
        if (session.isLocalAccount()) return Outcome.Success(0)
        val query = buildList {
            add("select" to "*")
            add("order" to "created_at.desc")
            add("limit" to "60")
            if (communityId != null) add("community_id" to "eq.$communityId")
        }
        return when (val response = api.select("feed", query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(
                        ListSerializer(FeedRowDto.serializer()),
                        response.value,
                    )
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Réponse illisible: ${it.message}")) }
                postDao.upsertAll(rows.map { it.toEntity() })
                Outcome.Success(rows.size)
            }
        }
    }

    suspend fun refreshComments(postId: String): Outcome<Int> {
        if (session.isLocalAccount()) return Outcome.Success(0)
        val query = listOf("post_id" to "eq.$postId", "select" to "*", "order" to "created_at.asc")
        return when (val response = api.select("post_comments", query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(CommentDto.serializer()), response.value)
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Commentaires illisibles")) }
                commentDao.upsertAll(rows.map { it.toEntity() })
                Outcome.Success(rows.size)
            }
        }
    }

    suspend fun createPost(
        body: String,
        communityId: String? = null,
        visibility: Visibility = Visibility.Public,
        media: ByteArray? = null,
        mediaType: String? = null,
        mimeType: String? = null,
        extension: String = "jpg",
    ): Outcome<Post> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && media == null) {
            return Outcome.Failure(AppError.Validation("empty_post", "Le message est vide."))
        }
        if (trimmed.length > MAX_POST_LENGTH) {
            return Outcome.Failure(
                AppError.Validation("post_too_long", "Maximum $MAX_POST_LENGTH caractères."),
            )
        }
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())

        val profile = profileDao.byId(userId)
        val now = clock()
        val postId = UUID.randomUUID().toString()

        // The bytes are uploaded before the row: a row pointing at a missing object would show a
        // broken post to everyone, and an outbox entry cannot replay bytes later.
        var mediaPath: String? = null
        if (media != null) {
            val path = "$userId/$postId.$extension"
            when (val uploaded = api.uploadObject("posts", path, media, mimeType ?: "image/jpeg")) {
                is Outcome.Failure -> return Outcome.Failure(
                    AppError.Unknown(
                        "Envoi du média impossible : connexion requise (stockage `posts` de la migration 0006).",
                    ),
                )
                is Outcome.Success -> mediaPath = path
            }
        }

        val entity = PostEntity(
            id = postId,
            authorId = userId,
            authorHandle = profile?.handle ?: "",
            authorDisplayName = profile?.displayName ?: "",
            body = trimmed,
            mediaPath = mediaPath,
            mediaType = if (mediaPath != null) mediaType else null,
            communityId = communityId,
            visibility = visibility,
            createdAt = now,
            updatedAt = now,
            reactionCount = 0,
            commentCount = 0,
            reactedByMe = false,
            pendingSync = true,
            deleted = false,
        )
        postDao.upsert(entity)

        val wire = PostInsert(
            id = postId,
            authorId = userId,
            body = trimmed,
            mediaPath = mediaPath,
            mediaType = if (mediaPath != null) mediaType else null,
            communityId = communityId,
            visibility = visibility.name,
        )
        val payload = SupabaseApi.json.encodeToString(PostInsert.serializer(), wire)
        if (SessionManager.isLocalId(userId)) {
            enqueue("post_insert", postId, payload)
            return Outcome.Success(entity.toDomain())
        }
        return when (val remote = api.insert("posts", payload, upsert = true, onConflict = "id")) {
            is Outcome.Success -> {
                postDao.markSynced(postId)
                Outcome.Success(entity.copy(pendingSync = false).toDomain())
            }
            is Outcome.Failure -> {
                enqueue("post_insert", postId, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    fun mediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val encoded = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "${tomate.data.backend.SupabaseConfig.url}/storage/v1/object/public/posts/$encoded"
    }

    /**
     * Modifie une de ses publications. La politique `posts_update_author` l'autorise déjà :
     * aucune migration n'est nécessaire. Hors ligne, la modification part par la file.
     */
    suspend fun editPost(postId: String, body: String): Outcome<Unit> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("empty_post", "Le message est vide."))
        }
        if (trimmed.length > MAX_POST_LENGTH) {
            return Outcome.Failure(AppError.Validation("post_too_long", "Maximum $MAX_POST_LENGTH caractères."))
        }
        val post = postDao.byId(postId) ?: return Outcome.Failure(AppError.NotFound())
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        if (post.authorId != me) {
            return Outcome.Failure(AppError.Validation("not_author", "Seul l'auteur peut modifier cette publication."))
        }
        postDao.upsert(post.copy(body = trimmed, updatedAt = clock(), pendingSync = true))
        val payload = """{"id":"$postId","body":${quoteJson(trimmed)}}"""
        if (SessionManager.isLocalId(me)) {
            enqueue("post_update", postId, payload)
            return Outcome.Success(Unit)
        }
        return when (val remote = api.update("posts", listOf("id" to "eq.$postId"), payload)) {
            is Outcome.Success -> {
                postDao.upsert(postDao.byId(postId)?.copy(body = trimmed, pendingSync = false) ?: post.copy(body = trimmed))
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> {
                enqueue("post_update", postId, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun deletePost(postId: String): Outcome<Unit> {
        postDao.markDeleted(postId)
        if (session.isLocalAccount()) {
            enqueue("post_delete", postId, """{"id":"$postId"}""")
            return Outcome.Success(Unit)
        }
        val remote = api.delete("posts", listOf("id" to "eq.$postId"))
        return when (remote) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                // The local deletion stays; the outbox retries the server side later.
                enqueue("post_delete", postId, """{"id":"$postId"}""")
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Adds or removes the "tomate" reaction. Optimistic locally, reconciled by the server. */
    suspend fun toggleReaction(postId: String, currentlyReacted: Boolean): Outcome<Boolean> {
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val post = postDao.byId(postId) ?: return Outcome.Failure(AppError.NotFound())
        val nextReacted = !currentlyReacted
        val nextCount = (post.reactionCount + if (nextReacted) 1 else -1).coerceAtLeast(0)
        postDao.updateReaction(postId, nextCount, nextReacted)
        if (SessionManager.isLocalId(userId)) {
            if (nextReacted) {
                val payload = SupabaseApi.json.encodeToString(
                    ReactionInsert.serializer(),
                    ReactionInsert(postId = postId, userId = userId),
                )
                enqueue("reaction_add", "$postId:$userId", payload)
            } else {
                enqueue("reaction_remove", "$postId:$userId", """{"post_id":"$postId","user_id":"$userId"}""")
            }
            return Outcome.Success(nextReacted)
        }

        return if (nextReacted) {
            val wire = ReactionInsert(postId = postId, userId = userId)
            val payload = SupabaseApi.json.encodeToString(ReactionInsert.serializer(), wire)
            when (val remote = api.insert("post_reactions", payload, ignoreDuplicates = true)) {
                is Outcome.Success -> Outcome.Success(true)
                is Outcome.Failure -> {
                    enqueue("reaction_add", "$postId:$userId", payload)
                    Outcome.Failure(remote.error)
                }
            }
        } else {
            val filter = listOf("post_id" to "eq.$postId", "user_id" to "eq.$userId")
            when (val remote = api.delete("post_reactions", filter)) {
                is Outcome.Success -> Outcome.Success(false)
                is Outcome.Failure -> {
                    enqueue("reaction_remove", "$postId:$userId", """{"post_id":"$postId","user_id":"$userId"}""")
                    Outcome.Failure(remote.error)
                }
            }
        }
    }

    /** Nombre de participants à un événement et participation de ce compte. */
    data class Participation(val going: Int, val mine: Boolean)

    /**
     * « Je participe » est une ligne `post_reactions` de kind `participe`, sans nouvelle table.
     * Le compte vient du serveur, jamais d'un compteur local : un événement ouvert ailleurs
     * affiche le même nombre ici.
     */
    suspend fun participation(postId: String): Outcome<Participation> {
        val me = session.currentUserId ?: return Outcome.Success(Participation(0, false))
        if (session.isLocalAccount()) return Outcome.Success(Participation(0, false))
        val query = listOf(
            "post_id" to "eq.$postId",
            "kind" to "eq.participe",
            "select" to "user_id",
        )
        return when (val response = api.select("post_reactions", query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val ids = runCatching {
                    SupabaseApi.json.parseToJsonElement(response.value).jsonArray.mapNotNull { row ->
                        row.jsonObject["user_id"]?.jsonPrimitive?.content
                    }
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Participations illisibles")) }
                Outcome.Success(Participation(going = ids.size, mine = ids.contains(me)))
            }
        }
    }

    /** Rejoint ou quitte un événement. Hors ligne, l'opération part par la file locale. */
    suspend fun setParticipation(postId: String, going: Boolean): Outcome<Unit> {
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val payload = SupabaseApi.json.encodeToString(
            ReactionInsert.serializer(),
            ReactionInsert(postId = postId, userId = userId, kind = PARTICIPATION_KIND),
        )
        val key = "$postId:$userId"
        if (SessionManager.isLocalId(userId)) {
            enqueue(if (going) "participation_add" else "participation_remove", key, payload)
            return Outcome.Success(Unit)
        }
        if (going) {
            return when (val remote = api.insert("post_reactions", payload, ignoreDuplicates = true)) {
                is Outcome.Success -> Outcome.Success(Unit)
                is Outcome.Failure -> {
                    enqueue("participation_add", key, payload)
                    Outcome.Failure(remote.error)
                }
            }
        }
        val filter = listOf(
            "post_id" to "eq.$postId",
            "user_id" to "eq.$userId",
            "kind" to "eq.$PARTICIPATION_KIND",
        )
        return when (val remote = api.delete("post_reactions", filter)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("participation_remove", key, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun addComment(postId: String, body: String, parentId: String? = null): Outcome<Comment> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("empty_comment", "Le commentaire est vide."))
        }
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val profile = profileDao.byId(userId)
        val now = clock()
        val commentId = UUID.randomUUID().toString()
        val entity = CommentEntity(
            id = commentId,
            postId = postId,
            authorId = userId,
            authorHandle = profile?.handle ?: "",
            body = trimmed.take(MAX_COMMENT_LENGTH),
            createdAt = now,
            parentId = parentId,
            pendingSync = true,
        )
        commentDao.upsert(entity)

        val payload = SupabaseApi.json.encodeToString(
            CommentInsert.serializer(),
            CommentInsert(commentId, postId, userId, entity.body, parentId),
        )
        if (SessionManager.isLocalId(userId)) {
            enqueue("comment_insert", commentId, payload)
            return Outcome.Success(entity.toDomain())
        }
        return when (val remote = api.insert("post_comments", payload, upsert = true, onConflict = "id")) {
            is Outcome.Success -> {
                commentDao.markSynced(commentId)
                Outcome.Success(entity.copy(pendingSync = false).toDomain())
            }
            is Outcome.Failure -> {
                enqueue("comment_insert", commentId, payload)
                Outcome.Failure(remote.error)
            }
        }
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
        const val MAX_POST_LENGTH = 2000
        const val MAX_COMMENT_LENGTH = 1000
        const val PARTICIPATION_KIND = "participe"
    }
}
