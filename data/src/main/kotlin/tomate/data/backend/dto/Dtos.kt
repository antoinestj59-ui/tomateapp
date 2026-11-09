package tomate.data.backend.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire DTOs. They mirror the SQL schema in supabase/migrations/ and nothing more. */

@Serializable
data class ProfileDto(
    val id: String,
    val handle: String,
    @SerialName("display_name") val displayName: String,
    val bio: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
    val visibility: String = "Public",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PostDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    @SerialName("community_id") val communityId: String? = null,
    val visibility: String = "Public",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** Feed row as returned by the `feed` view (post + author + counters). */
@Serializable
data class FeedRowDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_handle") val authorHandle: String = "",
    @SerialName("author_display_name") val authorDisplayName: String = "",
    val body: String,
    @SerialName("community_id") val communityId: String? = null,
    val visibility: String = "Public",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reaction_count") val reactionCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("reacted_by_me") val reactedByMe: Boolean = false,
)

@Serializable
data class PostInsert(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    @SerialName("community_id") val communityId: String? = null,
    val visibility: String = "Public",
)

/** Row from the `stories_feed` view: story + author + whether the reader already saw it. */
@Serializable
data class StoryRowDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_handle") val authorHandle: String = "",
    @SerialName("author_display_name") val authorDisplayName: String = "",
    val body: String,
    val background: String = "sunset",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("viewed_by_me") val viewedByMe: Boolean = false,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

@Serializable
data class StoryInsert(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    val background: String = "sunset",
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

@Serializable
data class StoryViewInsert(
    @SerialName("story_id") val storyId: String,
    @SerialName("viewer_id") val viewerId: String,
)

@Serializable
data class CommentDto(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_handle") val authorHandle: String = "",
    val body: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CommentInsert(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    @SerialName("parent_id") val parentId: String? = null,
)

@Serializable
data class ReactionInsert(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val kind: String = "tomate",
)

@Serializable
data class CommunityDto(
    val id: String,
    val name: String,
    val slug: String,
    val description: String = "",
    @SerialName("owner_id") val ownerId: String,
    val visibility: String = "Public",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CommunityMemberDto(
    @SerialName("community_id") val communityId: String,
    @SerialName("user_id") val userId: String,
    val role: String = "Member",
    val status: String = "Active",
)

@Serializable
data class MembershipRowDto(
    @SerialName("community_id") val communityId: String,
    val role: String,
    val status: String,
)

@Serializable
data class ConversationDto(
    val id: String,
    val title: String = "",
    @SerialName("is_group") val isGroup: Boolean = false,
    /** Required by the server (`created_by` is NOT NULL) and by the insert RLS policy. */
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
)

@Serializable
data class ConversationMemberDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String? = null,
    val encrypted: Boolean = false,
    @SerialName("reply_to_id") val replyToId: String? = null,
)

@Serializable
data class MessageInsert(
    val id: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    val encrypted: Boolean = false,
    @SerialName("reply_to_id") val replyToId: String? = null,
)

@Serializable
data class ReportInsert(
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
    val reason: String,
    val details: String = "",
)

@Serializable
data class BlockInsert(
    @SerialName("blocker_id") val blockerId: String,
    @SerialName("blocked_id") val blockedId: String,
)

@Serializable
data class HandleAvailability(val available: Boolean, val reason: String? = null)

@Serializable
data class AuthUserDto(
    val id: String,
    val aud: String? = null,
    val role: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AuthSessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: AuthUserDto,
)
