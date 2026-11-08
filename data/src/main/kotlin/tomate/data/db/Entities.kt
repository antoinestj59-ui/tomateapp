package tomate.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import tomate.core.model.CommunityRole
import tomate.core.model.MembershipStatus
import tomate.core.model.MessageDeliveryState
import tomate.core.model.TransportKind
import tomate.core.model.Visibility

/**
 * Local Room schema. Room is the source of truth for everything the user can already see:
 * the application must stay fully readable with no Internet connection.
 */

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val handle: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val visibility: Visibility,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean,
    val isMe: Boolean,
)

@Entity(
    tableName = "posts",
    indices = [Index("authorId"), Index("communityId"), Index("createdAt")],
)
data class PostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorHandle: String,
    val authorDisplayName: String,
    val body: String,
    val communityId: String?,
    val visibility: Visibility,
    val createdAt: Long,
    val updatedAt: Long,
    val reactionCount: Int,
    val commentCount: Int,
    val reactedByMe: Boolean,
    val pendingSync: Boolean,
    val deleted: Boolean,
)

@Entity(tableName = "comments", indices = [Index("postId"), Index("createdAt")])
data class CommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,
    val authorHandle: String,
    val body: String,
    val createdAt: Long,
    val parentId: String?,
    val pendingSync: Boolean,
)

@Entity(tableName = "communities", indices = [Index(value = ["slug"], unique = true)])
data class CommunityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val slug: String,
    val description: String,
    val ownerId: String,
    val visibility: Visibility,
    val memberCount: Int,
    val myRole: CommunityRole?,
    val myMembership: MembershipStatus?,
)

@Entity(
    tableName = "conversations",
    indices = [Index("lastMessageAt")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
    val pendingCount: Int,
)

/**
 * A conversation member row. Stored separately so group conversations and recipient routing
 * work without parsing JSON blobs.
 */
@Entity(
    tableName = "conversation_members",
    primaryKeys = ["conversationId", "memberId"],
    indices = [Index("memberId")],
)
data class ConversationMemberEntity(
    val conversationId: String,
    val memberId: String,
    val displayName: String,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("createdAt"), Index(value = ["clientId"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val deliveryState: MessageDeliveryState,
    val transport: TransportKind?,
    val encrypted: Boolean,
    val replyToId: String?,
    val attemptCount: Int,
    val lastError: String?,
)

/**
 * Durable outbox: every mutation that must reach a server or a peer is written here first,
 * with an idempotency key, then retried with exponential backoff.
 */
@Entity(tableName = "outbox", indices = [Index("createdAt"), Index(value = ["idempotencyKey"], unique = true)])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val idempotencyKey: String,
    val kind: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** Blocked accounts, mirrored locally so the UI can filter instantly while offline. */
@Entity(tableName = "blocked_users")
data class BlockedUserEntity(
    @PrimaryKey val userId: String,
    val handle: String,
    val blockedAt: Long,
)

/** Cached community membership, used to gate community-only content offline. */
@Entity(tableName = "community_members_cache", primaryKeys = ["communityId", "userId"])
data class CommunityMemberCacheEntity(
    val communityId: String,
    val userId: String,
    val role: CommunityRole,
    val status: MembershipStatus,
)

/** Aggregate row used by the message list screen. */
data class ConversationSummaryRow(
    @Embedded val conversation: ConversationEntity,
)
