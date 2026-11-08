package tomate.core.model

import java.util.UUID

/** Stable identifier helpers. The account UUID is the only permanent identity in TOMATE. */
object Ids {
    fun newMessageId(): String = UUID.randomUUID().toString()

    fun newConversationId(): String = UUID.randomUUID().toString()

    fun newPostId(): String = UUID.randomUUID().toString()
}

enum class Visibility { Public, Followers, Community, Private }

enum class NetworkStatus { Online, Offline, Checking }

enum class MessageDeliveryState { Pending, SentToTransport, Delivered, Read, Failed }

enum class TransportKind { Internet, BluetoothLe, WifiLocal, Relay }

enum class CommunityRole { Owner, Moderator, Member }

enum class MembershipStatus { Active, Pending, Rejected, Banned }

fun MembershipStatus.label(): String = when (this) {
    MembershipStatus.Active -> "Membre"
    MembershipStatus.Pending -> "En attente"
    MembershipStatus.Rejected -> "Refusé"
    MembershipStatus.Banned -> "Banni"
}

data class Profile(
    val id: String,
    val handle: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val visibility: Visibility = Visibility.Public,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** True while the profile has never been published to Supabase (offline creation). */
    val pendingSync: Boolean = false,
)

data class Post(
    val id: String,
    val authorId: String,
    val authorHandle: String = "",
    val authorDisplayName: String = "",
    val body: String,
    val communityId: String? = null,
    val visibility: Visibility = Visibility.Public,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val reactionCount: Int = 0,
    val commentCount: Int = 0,
    val reactedByMe: Boolean = false,
    val pendingSync: Boolean = false,
    val deleted: Boolean = false,
)

data class Comment(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorHandle: String = "",
    val body: String,
    val createdAt: Long = 0L,
    val parentId: String? = null,
    val pendingSync: Boolean = false,
)

data class Community(
    val id: String,
    val name: String,
    val slug: String,
    val description: String = "",
    val ownerId: String = "",
    val visibility: Visibility = Visibility.Public,
    val memberCount: Int = 0,
    val myRole: CommunityRole? = null,
    val myMembership: MembershipStatus? = null,
)

data class Conversation(
    val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = 0L,
    val unreadCount: Int = 0,
    val pendingCount: Int = 0,
    val members: List<String> = emptyList(),
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.Pending,
    val transport: TransportKind? = null,
    val encrypted: Boolean = false,
    val replyToId: String? = null,
)

/** A peer discovered on a nearby transport. Never persisted beyond the discovery window. */
data class NearbyPeer(
    val peerId: String,
    val displayName: String,
    val transport: TransportKind,
    val rssi: Int? = null,
    val lastSeenAt: Long = 0L,
    val paired: Boolean = false,
)

/**
 * Transport-agnostic envelope. [payload] is the encoded message; when E2EE is enabled it holds
 * ciphertext only (see docs/04_SECURITE.md — E2EE is not operational in this version).
 */
data class MessageEnvelope(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String?,
    val createdAt: Long,
    val expiresAt: Long?,
    val hopCount: Int = 0,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val payload: String,
    val signature: String? = null,
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_HOPS = 4
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

data class BlockedUser(
    val userId: String,
    val handle: String = "",
    val blockedAt: Long = 0L,
)

data class ReportDraft(
    val targetType: ReportTargetType,
    val targetId: String,
    val reason: ReportReason,
    val details: String = "",
)

enum class ReportTargetType { User, Post, Comment, Community, Message }

enum class ReportReason(val label: String) {
    Spam("Spam ou contenu répétitif"),
    Harassment("Harcèlement ou intimidation"),
    Hate("Discours haineux"),
    Violence("Violence ou menace"),
    Sexual("Contenu sexuel"),
    Misinformation("Désinformation"),
    Other("Autre"),
}

/** Human-readable French labels for the visibility enum. */
val Visibility.label: String
    get() = when (this) {
        Visibility.Public -> "Public"
        Visibility.Followers -> "Abonnés"
        Visibility.Community -> "Communauté"
        Visibility.Private -> "Privé"
    }

val MessageDeliveryState.label: String
    get() = when (this) {
        MessageDeliveryState.Pending -> "En attente"
        MessageDeliveryState.SentToTransport -> "Transmis"
        MessageDeliveryState.Delivered -> "Reçu"
        MessageDeliveryState.Read -> "Lu"
        MessageDeliveryState.Failed -> "Échec"
    }
