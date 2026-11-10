package tomate.app.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import tomate.core.designsystem.components.clockTime
import tomate.core.designsystem.components.compactCount
import tomate.core.designsystem.components.humanTime
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

/** Icône de portée d'une publication : globe, personnes ou cadenas (jamais un texte brut). */
private fun visibilityIcon(visibility: Visibility) = when (visibility) {
    Visibility.Public -> Icons.Filled.Public
    Visibility.Followers -> Icons.Filled.People
    Visibility.Community -> Icons.Filled.Groups
    Visibility.Private -> Icons.Filled.Lock
}

/**
 * Carte de publication expressive, proche des maquettes : identité en haut, média plein cadre,
 * rangée d'actions (réagir, commenter, partager, menu) alignée sur une seule ligne.
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
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(
                        start = TomateSpacing.m,
                        end = TomateSpacing.s,
                        top = TomateSpacing.m,
                        bottom = TomateSpacing.s,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (post.authorHandle.isBlank()) {
                    TomateLogo(size = 44.dp)
                } else {
                    TomateAvatar(label = post.authorDisplayName.ifBlank { post.authorHandle }, size = 46)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = humanTime(post.createdAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(TomateSpacing.xs))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(TomateSpacing.xs))
                        Icon(
                            imageVector = visibilityIcon(post.visibility),
                            contentDescription = post.visibility.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        if (post.pendingSync) {
                            Spacer(Modifier.width(TomateSpacing.xs))
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = TomateTheme.status.warning,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
                if (onEdit != null || onDelete != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Options de la publication",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text("Modifier") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onEdit()
                                    },
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Supprimer") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            val event = EventCodec.decodeEvent(post.body)
            if (event != null) {
                Spacer(Modifier.height(TomateSpacing.s))
                EventBlock(
                    event = event,
                    going = eventGoing,
                    mine = eventGoingByMe,
                    onToggle = onParticipate,
                )
            } else if (post.body.isNotBlank()) {
                Text(
                    text = post.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(
                        // Aligné sur le nom de l'auteur, comme dans les maquettes.
                        start = 70.dp,
                        end = TomateSpacing.m,
                        bottom = TomateSpacing.s,
                    ).clickable(onClick = onOpen),
                )
            }

            postMediaPublicUrl(post.mediaPath)?.let { url ->
                if (post.mediaType == "video") {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = TomateSpacing.s)
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
                    // Média plein cadre : c'est ce qui donne sa présence à la carte dans la maquette.
                    TomateRemoteImage(
                        url = url,
                        contentDescription = "Photo de ${post.authorDisplayName.ifBlank { post.authorHandle }}",
                        modifier = Modifier
                            .padding(horizontal = TomateSpacing.s)
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(MaterialTheme.shapes.large)
                            .clickable(onClick = onOpen),
                    )
                }
            }

            if (post.pendingSync) {
                Text(
                    text = "En attente d'envoi",
                    style = MaterialTheme.typography.labelSmall,
                    color = TomateTheme.status.warning,
                    modifier = Modifier.padding(start = TomateSpacing.m, top = TomateSpacing.xs),
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = TomateSpacing.s, vertical = TomateSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionPill(
                    icon = if (post.reactedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = compactCount(post.reactionCount),
                    contentDescription = if (post.reactedByMe) "Retirer la réaction" else "Réagir",
                    tint = if (post.reactedByMe) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReact,
                )
                ActionPill(
                    icon = Icons.Filled.ModeComment,
                    label = compactCount(post.commentCount),
                    contentDescription = "Commentaires",
                    onClick = onOpen,
                )
                ActionPill(
                    icon = Icons.Filled.Share,
                    label = "Partager",
                    contentDescription = "Partager",
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, post.body.ifBlank { "Publication TOMATE" })
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(share, "Partager la publication"))
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Action compacte icône + libellé, comme dans la rangée d'actions des maquettes. */
@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.s, vertical = TomateSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(TomateSpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TomateAvatar(label = conversation.title, size = 52)
        Spacer(Modifier.width(TomateSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
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
        Spacer(Modifier.width(TomateSpacing.s))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (conversation.streak > 0) {
                    "${clockTime(conversation.lastMessageAt)} · 🔥${conversation.streak}"
                } else {
                    clockTime(conversation.lastMessageAt)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (conversation.unreadCount > 0) {
                Spacer(Modifier.height(TomateSpacing.xs))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                ) {
                    Text(
                        text = compactCount(conversation.unreadCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            } else if (conversation.pendingCount > 0) {
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(
                    text = "${conversation.pendingCount} en attente",
                    style = MaterialTheme.typography.labelSmall,
                    color = TomateTheme.status.warning,
                )
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
