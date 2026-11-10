package tomate.app.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateAvatar
import tomate.core.designsystem.components.TomateLogo
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.designsystem.theme.TomateTheme
import tomate.core.model.Conversation
import tomate.core.model.Message
import tomate.core.model.MessageDeliveryState
import tomate.core.model.Post
import tomate.data.extras.EventCodec
import tomate.core.model.Visibility
import tomate.core.model.label

private val timeFormat = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

/** Public URL of a post attachment, built from the storage path kept in the database. */
fun postMediaPublicUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val encoded = path.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
    return "${tomate.data.backend.SupabaseConfig.url}/storage/v1/object/public/posts/$encoded"
}

/** Minimal remote image loader: no image library is bundled, bytes are decoded off the main thread. */
@Composable
fun TomateRemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

fun Long.formatTimestamp(): String =
    if (this <= 0L) "—" else timeFormat.format(Date(this))

/**
 * Carte de publication expressive : surface tonale, hiérarchie resserrée, logo en repli.
 * Plus d'accumulation de cartes génériques : une seule surface par publication.
 */
@Composable
fun PostCard(
    post: Post,
    onOpen: () -> Unit,
    onReact: () -> Unit,
    modifier: Modifier = Modifier,
    eventGoing: Int? = null,
    eventGoingByMe: Boolean = false,
    onParticipate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(TomateSpacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.authorHandle.isBlank()) {
                    TomateLogo(size = 44.dp)
                } else {
                    TomateAvatar(label = post.authorDisplayName.ifBlank { post.authorHandle })
                }
                Spacer(Modifier.width(TomateSpacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = post.authorDisplayName.ifBlank { post.authorHandle.ifBlank { "Anonyme" } },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "@${post.authorHandle.ifBlank { "…" }} · ${post.createdAt.formatTimestamp()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (post.visibility != Visibility.Public) {
                    MetricPill(text = post.visibility.label)
                }
            }
            val event = EventCodec.decodeEvent(post.body)
            if (event != null) {
                Spacer(Modifier.height(TomateSpacing.m))
                EventBlock(
                    event = event,
                    going = eventGoing,
                    mine = eventGoingByMe,
                    onToggle = onParticipate,
                )
            } else if (post.body.isNotBlank()) {
                Spacer(Modifier.height(TomateSpacing.m))
                Text(text = post.body, style = MaterialTheme.typography.bodyLarge)
            }

            postMediaPublicUrl(post.mediaPath)?.let { url ->
                Spacer(Modifier.height(TomateSpacing.m))
                if (post.mediaType == "video") {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                            .padding(TomateSpacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(TomateSpacing.s))
                        Text(text = "Vidéo — ouvrir le lecteur", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    TomateRemoteImage(
                        url = url,
                        contentDescription = "Photo de ${post.authorDisplayName.ifBlank { post.authorHandle }}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(MaterialTheme.shapes.large),
                    )
                }
            }

            Spacer(Modifier.height(TomateSpacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.pendingSync) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = TomateTheme.status.warning,
                    )
                    Spacer(Modifier.width(TomateSpacing.xs))
                    Text(
                        text = "En attente d'envoi",
                        style = MaterialTheme.typography.labelSmall,
                        color = TomateTheme.status.warning,
                    )
                    Spacer(Modifier.width(TomateSpacing.m))
                }
                IconButton(onClick = onReact, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (post.reactedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (post.reactedByMe) "Retirer la réaction" else "Réagir",
                        tint = if (post.reactedByMe) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = post.reactionCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(TomateSpacing.l))
                Icon(
                    imageVector = Icons.Filled.ModeComment,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(TomateSpacing.xs))
                Text(
                    text = post.commentCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (onEdit != null) {
                    AssistChip(
                        onClick = onEdit,
                        label = { Text("Modifier") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(TomateSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TomateAvatar(label = conversation.title)
            Spacer(Modifier.width(TomateSpacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (conversation.streak > 0) {
                        "${conversation.title} · 🔥 ${conversation.streak}"
                    } else {
                        conversation.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = conversation.lastMessagePreview.ifBlank { "Aucun message" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = conversation.lastMessageAt.formatTimestamp(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.height(TomateSpacing.xs))
                    MetricPill(text = conversation.unreadCount.toString())
                }
                if (conversation.pendingCount > 0) {
                    Spacer(Modifier.height(TomateSpacing.xs))
                    MetricPill(text = "${conversation.pendingCount} en attente")
                }
            }
        }
    }
}

/**
 * Bulle expressive : asymétrique, citation de réponse, état de livraison honnête.
 * [replyPreview] est le début du message cité (résolu par l'écran depuis la liste).
 */
@Composable
fun MessageBubble(
    message: Message,
    isMine: Boolean,
    modifier: Modifier = Modifier,
    replyPreview: String? = null,
    onReply: (() -> Unit)? = null,
) {
    val container = if (isMine) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = if (isMine) {
                MaterialTheme.shapes.large.copy(
                    bottomEnd = androidx.compose.foundation.shape.CornerSize(6.dp),
                )
            } else {
                MaterialTheme.shapes.large.copy(
                    bottomStart = androidx.compose.foundation.shape.CornerSize(6.dp),
                )
            },
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(horizontal = TomateSpacing.m, vertical = TomateSpacing.s)) {
                val quote = replyPreview ?: message.replyToId?.let { "Réponse à un message" }
                if (quote != null) {
                    Surface(
                        color = content.copy(alpha = 0.10f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = quote.take(120),
                            style = MaterialTheme.typography.labelMedium,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(TomateSpacing.s),
                        )
                    }
                    Spacer(Modifier.height(TomateSpacing.xs))
                }
                Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(TomateSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${message.createdAt.formatTimestamp()} · ${message.deliveryState.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    if (!message.encrypted) {
                        Text(
                            text = "· non chiffré E2EE",
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                        )
                    }
                    if (onReply != null) {
                        IconButton(onClick = onReply, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Reply,
                                contentDescription = "Répondre",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small label used where a feature is deliberately not available yet. */
@Composable
fun UnavailableNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

val MessageDeliveryState.isPending: Boolean
    get() = this == MessageDeliveryState.Pending || this == MessageDeliveryState.SentToTransport
