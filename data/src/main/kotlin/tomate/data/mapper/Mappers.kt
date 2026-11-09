package tomate.data.mapper

import java.time.Instant
import java.time.OffsetDateTime
import tomate.core.model.Comment
import tomate.core.model.Community
import tomate.core.model.CommunityRole
import tomate.core.model.Conversation
import tomate.core.model.MembershipStatus
import tomate.core.model.Message
import tomate.core.model.MessageDeliveryState
import tomate.core.model.Post
import tomate.core.model.Profile
import tomate.core.model.Story
import tomate.core.model.TransportKind
import tomate.core.model.Visibility
import tomate.data.backend.dto.CommentDto
import tomate.data.backend.dto.CommunityDto
import tomate.data.backend.dto.ConversationDto
import tomate.data.backend.dto.FeedRowDto
import tomate.data.backend.dto.MessageDto
import tomate.data.backend.dto.ProfileDto
import tomate.data.backend.dto.StoryRowDto
import tomate.data.db.CommentEntity
import tomate.data.db.CommunityEntity
import tomate.data.db.ConversationEntity
import tomate.data.db.MessageEntity
import tomate.data.db.PostEntity
import tomate.data.db.ProfileEntity
import tomate.data.db.StoryEntity

/** Supabase returns `2026-11-08T20:59:51.01234+00:00`; Room stores epoch millis. */
fun isoToMillis(value: String?): Long = value?.let { raw ->
    runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrElse { runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L) }
} ?: 0L

fun visibilityFromWire(value: String?): Visibility = when (value?.lowercase()) {
    "followers" -> Visibility.Followers
    "community" -> Visibility.Community
    "private" -> Visibility.Private
    else -> Visibility.Public
}

fun StoryRowDto.toEntity(): StoryEntity = StoryEntity(
    id = id,
    authorId = authorId,
    authorHandle = authorHandle,
    authorDisplayName = authorDisplayName,
    body = body,
    background = background,
    createdAt = isoToMillis(createdAt),
    expiresAt = isoToMillis(expiresAt),
    viewedByMe = viewedByMe,
    pendingSync = false,
    mediaPath = mediaPath,
    mediaType = mediaType,
)

fun StoryEntity.toDomain(): Story = Story(
    id = id,
    authorId = authorId,
    authorHandle = authorHandle,
    authorDisplayName = authorDisplayName,
    body = body,
    background = background,
    createdAt = createdAt,
    expiresAt = expiresAt,
    viewedByMe = viewedByMe,
    pendingSync = pendingSync,
    mediaPath = mediaPath,
    mediaType = mediaType,
)

fun ProfileDto.toEntity(isMe: Boolean): ProfileEntity = ProfileEntity(
    id = id,
    handle = handle,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarPath,
    visibility = visibilityFromWire(visibility),
    createdAt = isoToMillis(createdAt),
    updatedAt = isoToMillis(updatedAt),
    pendingSync = false,
    isMe = isMe,
)

fun ProfileEntity.toDomain(): Profile = Profile(
    id = id,
    handle = handle,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    visibility = visibility,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pendingSync = pendingSync,
)

fun FeedRowDto.toEntity(): PostEntity = PostEntity(
    id = id,
    authorId = authorId,
    authorHandle = authorHandle,
    authorDisplayName = authorDisplayName.ifBlank { authorHandle },
    body = body,
    mediaPath = mediaPath,
    mediaType = mediaType,
    communityId = communityId,
    visibility = visibilityFromWire(visibility),
    createdAt = isoToMillis(createdAt),
    updatedAt = isoToMillis(createdAt),
    reactionCount = reactionCount,
    commentCount = commentCount,
    reactedByMe = reactedByMe,
    pendingSync = false,
    deleted = false,
)

fun PostEntity.toDomain(): Post = Post(
    id = id,
    authorId = authorId,
    authorHandle = authorHandle,
    authorDisplayName = authorDisplayName,
    body = body,
    mediaPath = mediaPath,
    mediaType = mediaType,
    communityId = communityId,
    visibility = visibility,
    createdAt = createdAt,
    updatedAt = updatedAt,
    reactionCount = reactionCount,
    commentCount = commentCount,
    reactedByMe = reactedByMe,
    pendingSync = pendingSync,
    deleted = deleted,
)

fun CommentDto.toEntity(): CommentEntity = CommentEntity(
    id = id,
    postId = postId,
    authorId = authorId,
    authorHandle = authorHandle,
    body = body,
    createdAt = isoToMillis(createdAt),
    parentId = parentId,
    pendingSync = false,
)

fun CommentEntity.toDomain(): Comment = Comment(
    id = id,
    postId = postId,
    authorId = authorId,
    authorHandle = authorHandle,
    body = body,
    createdAt = createdAt,
    parentId = parentId,
    pendingSync = pendingSync,
)

fun CommunityDto.toEntity(role: CommunityRole? = null, membership: MembershipStatus? = null): CommunityEntity =
    CommunityEntity(
        id = id,
        name = name,
        slug = slug,
        description = description,
        ownerId = ownerId,
        visibility = visibilityFromWire(visibility),
        memberCount = memberCount,
        myRole = role,
        myMembership = membership,
    )

fun CommunityEntity.toDomain(): Community = Community(
    id = id,
    name = name,
    slug = slug,
    description = description,
    ownerId = ownerId,
    visibility = visibility,
    memberCount = memberCount,
    myRole = myRole,
    myMembership = myMembership,
)

fun ConversationDto.toEntity(unread: Int = 0, pending: Int = 0, preview: String = ""): ConversationEntity =
    ConversationEntity(
        id = id,
        title = title.ifBlank { "Conversation" },
        isGroup = isGroup,
        lastMessagePreview = preview,
        lastMessageAt = isoToMillis(lastMessageAt ?: createdAt),
        unreadCount = unread,
        pendingCount = pending,
    )

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    isGroup = isGroup,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAt,
    unreadCount = unreadCount,
    pendingCount = pendingCount,
)

fun MessageDto.toEntity(myId: String): MessageEntity = MessageEntity(
    id = id,
    clientId = clientId,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
    createdAt = isoToMillis(createdAt),
    deliveryState = if (senderId == myId) MessageDeliveryState.Delivered else MessageDeliveryState.Read,
    transport = TransportKind.Internet,
    encrypted = encrypted,
    replyToId = replyToId,
    attemptCount = 0,
    lastError = null,
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
    createdAt = createdAt,
    deliveryState = deliveryState,
    transport = transport,
    encrypted = encrypted,
    replyToId = replyToId,
)
