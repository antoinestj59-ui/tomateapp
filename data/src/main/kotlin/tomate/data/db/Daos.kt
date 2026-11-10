package tomate.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isMe = 1 LIMIT 1")
    fun observeMe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isMe = 1 LIMIT 1")
    suspend fun me(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isMe = 0 AND (handle LIKE '%' || :query || '%' OR displayName LIKE '%' || :query || '%')")
    fun search(query: String): Flow<List<ProfileEntity>>

    @Query("UPDATE profiles SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM profiles WHERE id = :id AND isMe = 0")
    suspend fun deleteCached(id: String)

    @Query("DELETE FROM profiles WHERE isMe = 1")
    suspend fun deleteMe()

    /** Rewrites a local identity (`local-…`) into the server account id. */
    @Query("UPDATE profiles SET id = :newId, pendingSync = 1 WHERE id = :oldId")
    suspend fun rekey(oldId: String, newId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE handle = :handle AND isMe = 0)")
    suspend fun handleTaken(handle: String): Boolean
}

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(posts: List<PostEntity>)

    @Query("SELECT * FROM posts WHERE deleted = 0 AND (:communityId IS NULL OR communityId = :communityId) ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun observeFeed(communityId: String?, limit: Int, offset: Int): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PostEntity?>

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PostEntity?

    @Query("SELECT * FROM posts WHERE authorId = :authorId AND deleted = 0 ORDER BY createdAt DESC")
    fun observeByAuthor(authorId: String): Flow<List<PostEntity>>

    @Query("UPDATE posts SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE posts SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("UPDATE posts SET reactionCount = :count, reactedByMe = :reacted WHERE id = :id")
    suspend fun updateReaction(id: String, count: Int, reacted: Boolean)

    @Query("UPDATE posts SET commentCount = :count WHERE id = :id")
    suspend fun updateCommentCount(id: String, count: Int)

    @Query("DELETE FROM posts WHERE pendingSync = 0")
    suspend fun clearSynced()

    @Query("UPDATE posts SET authorId = :newId WHERE authorId = :oldId")
    suspend fun reassignAuthor(oldId: String, newId: String)
}

@Dao
interface CommentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(comment: CommentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(comments: List<CommentEntity>)

    @Query("SELECT * FROM comments WHERE postId = :postId ORDER BY createdAt ASC")
    fun observeForPost(postId: String): Flow<List<CommentEntity>>

    @Query("UPDATE comments SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM comments WHERE postId = :postId AND pendingSync = 0")
    suspend fun clearSyncedForPost(postId: String)

    @Query("UPDATE comments SET authorId = :newId WHERE authorId = :oldId")
    suspend fun reassignAuthor(oldId: String, newId: String)
}

@Dao
interface CommunityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(community: CommunityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(communities: List<CommunityEntity>)

    @Query("SELECT * FROM communities ORDER BY memberCount DESC, name ASC")
    fun observeAll(): Flow<List<CommunityEntity>>

    @Query("SELECT * FROM communities WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<CommunityEntity?>

    @Query("SELECT * FROM communities WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): CommunityEntity?

    @Query("SELECT * FROM communities WHERE myMembership = 'Active'")
    fun observeMine(): Flow<List<CommunityEntity>>

    @Query("DELETE FROM communities")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: CommunityMemberCacheEntity)

    @Query("SELECT * FROM community_members_cache WHERE communityId = :communityId")
    suspend fun members(communityId: String): List<CommunityMemberCacheEntity>

    @Query("UPDATE communities SET ownerId = :newId WHERE ownerId = :oldId")
    suspend fun reassignOwner(oldId: String, newId: String)

    @Query("UPDATE community_members_cache SET userId = :newId WHERE userId = :oldId")
    suspend fun reassignMemberCache(oldId: String, newId: String)
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<ConversationMemberEntity>)

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation_members WHERE conversationId = :conversationId")
    suspend fun members(conversationId: String): List<ConversationMemberEntity>

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageAt = :at, pendingCount = :pending WHERE id = :id")
    suspend fun updateLastMessage(id: String, preview: String, at: Long, pending: Int)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("UPDATE conversation_members SET memberId = :newId WHERE memberId = :oldId")
    suspend fun reassignMember(oldId: String, newId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversation_members WHERE conversationId = :id")
    suspend fun deleteMembers(id: String)
}

/** One row per conversation per UTC day, with the number of distinct senders that day. */
data class StreakDayRow(
    val conversationId: String,
    val dayIndex: Long,
    val senders: Int,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastMessage(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE clientId = :clientId LIMIT 1")
    suspend fun byClientId(clientId: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :state, transport = :transport, lastError = :error WHERE id = :id")
    suspend fun updateDelivery(id: String, state: String, transport: String?, error: String?)

    @Query("UPDATE messages SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordAttempt(id: String, error: String?)

    @Query("UPDATE messages SET deliveryState = 'Read' WHERE conversationId = :conversationId AND senderId != :me")
    suspend fun markConversationRead(conversationId: String, me: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND deliveryState IN ('Pending','Failed')")
    suspend fun pendingCount(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE deliveryState = 'Pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM messages WHERE deliveryState = 'Pending' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingMessages(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET senderId = :newId WHERE senderId = :oldId")
    suspend fun reassignSender(oldId: String, newId: String)

    @Query(
        """
        SELECT conversationId AS conversationId,
               (createdAt / 86400000) AS dayIndex,
               COUNT(DISTINCT senderId) AS senders
          FROM messages
         GROUP BY conversationId, dayIndex
         ORDER BY dayIndex DESC
        """,
    )
    fun observeStreakDays(): Flow<List<StreakDayRow>>
}

@Dao
interface StoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(story: StoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stories: List<StoryEntity>)

    /** Active stories only: the 24-hour window is enforced by the query, not by the UI. */
    @Query("SELECT * FROM stories WHERE expiresAt > :now ORDER BY createdAt ASC")
    fun observeActive(now: Long): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): StoryEntity?

    @Query("SELECT * FROM stories")
    suspend fun all(): List<StoryEntity>

    @Query("UPDATE stories SET viewedByMe = 1 WHERE id = :id")
    suspend fun markViewed(id: String)

    @Query("UPDATE stories SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM stories WHERE expiresAt <= :now AND pendingSync = 0")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM stories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE stories SET authorId = :newId WHERE authorId = :oldId")
    suspend fun reassignAuthor(oldId: String, newId: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun total(): Int

    /**
     * Seules les opérations encore vivantes sont comptées : une entrée morte (voir [DEAD]) a
     * épuisé ses tentatives et ne partira plus jamais toute seule. La compter laissait le
     * bandeau « N opérations en attente » affiché indéfiniment alors que plus rien n'avançait.
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE nextAttemptAt < 9223372036854775807")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE nextAttemptAt < 9223372036854775807")
    suspend fun count(): Int

    /** Opérations définitivement refusées : visibles, mais hors du décompte « en attente ». */
    @Query("SELECT COUNT(*) FROM outbox WHERE nextAttemptAt = 9223372036854775807")
    fun observeDeadCount(): Flow<Int>

    @Query("SELECT * FROM outbox WHERE nextAttemptAt = 9223372036854775807 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun dead(limit: Int = 20): List<OutboxEntity>

    /** La dernière raison de refus, pour l'afficher telle quelle à l'utilisateur. */
    @Query(
        "SELECT lastError FROM outbox WHERE nextAttemptAt = 9223372036854775807 " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    fun observeLastFailure(): Flow<String?>

    /** Remet les opérations refusées dans la file, avec un compteur de tentatives remis à zéro. */
    @Query("UPDATE outbox SET attempts = 0, nextAttemptAt = 0, lastError = NULL WHERE nextAttemptAt = 9223372036854775807")
    suspend fun reviveDead(): Int

    /** Abandonne définitivement les opérations refusées, sur demande explicite de l'utilisateur. */
    @Query("DELETE FROM outbox WHERE nextAttemptAt = 9223372036854775807")
    suspend fun discardDead(): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun done(id: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id")
    suspend fun reschedule(id: String, nextAttemptAt: Long, error: String?)

    @Query("SELECT * FROM outbox WHERE kind = :kind AND idempotencyKey = :key LIMIT 1")
    suspend fun byKey(kind: String, key: String): OutboxEntity?

    /** Rewrites a local identity inside queued JSON payloads before they are sent. */
    @Query("UPDATE outbox SET payload = REPLACE(payload, :oldId, :newId) WHERE payload LIKE '%' || :oldId || '%'")
    suspend fun rekeyPayload(oldId: String, newId: String)
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)

    @Query("SELECT value FROM sync_state WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_state")
    fun observeAll(): Flow<List<SyncStateEntity>>
}

@Dao
interface BlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(entry: BlockedUserEntity)

    @Query("DELETE FROM blocked_users WHERE userId = :userId")
    suspend fun unblock(userId: String)

    @Query("SELECT * FROM blocked_users ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedUserEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE userId = :userId)")
    suspend fun isBlocked(userId: String): Boolean
}

@Dao
interface MaintenanceDao {
    /** Local retention: delivered messages older than the window are pruned. */
    @Query("DELETE FROM messages WHERE createdAt < :before AND deliveryState != 'Pending'")
    suspend fun pruneOldMessages(before: Long)
}
