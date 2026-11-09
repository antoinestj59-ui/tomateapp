package tomate.app.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tomate.app.ui.components.PostCard
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateNetworkBadge
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.NetworkStatus
import tomate.core.model.Story
import tomate.data.repo.StoryRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    network: NetworkStatus,
    pendingOperations: Int,
    onSyncNow: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = tomateViewModelFactory { HomeViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openStoryId by remember { mutableStateOf<String?>(null) }
    val openStory = state.stories.firstOrNull { it.id == openStoryId }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = "TOMATE", style = MaterialTheme.typography.headlineMedium) },
            actions = {
                TomateNetworkBadge(status = network)
                IconButton(onClick = onSyncNow) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Synchroniser maintenant")
                }
            },
        )

        if (state.error != null) {
            TomateInlineNotice(
                text = state.error!!.userMessage,
                tone = NoticeTone.Error,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        if (state.network == NetworkStatus.Offline) {
            TomateInlineNotice(
                text = "Hors ligne : le fil affiché est celui déjà téléchargé. Vos publications et stories partiront automatiquement au retour du réseau.",
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        if (pendingOperations > 0) {
            TomateInlineNotice(
                text = "$pendingOperations opération(s) en attente de synchronisation.",
                tone = NoticeTone.Info,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }

        StoriesStrip(
            stories = state.stories,
            myId = state.myId,
            onAdd = viewModel::openStoryComposer,
            onOpen = { story ->
                openStoryId = story.id
                viewModel.markStoryViewed(story)
            },
        )

        TomateCard(modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s)) {
            Column(modifier = Modifier.padding(TomateSpacing.m)) {
                TomateTextField(
                    value = state.composerText,
                    onValueChange = viewModel::onComposerChange,
                    label = "Partager quelque chose…",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                )
                Spacer(Modifier.height(TomateSpacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricPill(text = "${state.composerText.length}/2000")
                    Spacer(Modifier.width(TomateSpacing.xs))
                    IconButton(
                        onClick = viewModel::publish,
                        enabled = state.composerText.isNotBlank() && !state.posting,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publier")
                    }
                }
            }
        }

        when {
            state.loading -> TomateLoadingState()
            state.posts.isEmpty() -> TomateEmptyState(
                title = "Votre fil est vide",
                message = if (state.network == NetworkStatus.Offline) {
                    "Aucune publication téléchargée n'est encore disponible hors ligne."
                } else {
                    "Publiez un premier message ou abonnez-vous à d'autres comptes."
                },
                actionLabel = "Actualiser",
                onAction = viewModel::refresh,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = TomateSpacing.l,
                    end = TomateSpacing.l,
                    top = TomateSpacing.s,
                    bottom = TomateSpacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
            ) {
                items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onOpen = { onOpenPost(post.id) },
                        onReact = { viewModel.react(post) },
                    )
                }
            }
        }
    }

    if (openStory != null) {
        StoryViewer(
            story = openStory,
            mediaUrl = viewModel.mediaUrl(openStory),
            mine = openStory.authorId == state.myId,
            onDismiss = { openStoryId = null },
            onDelete = {
                viewModel.deleteStory(openStory)
                openStoryId = null
            },
        )
        LaunchedEffect(openStory.id) { viewModel.markStoryViewed(openStory) }
    }

    state.storyDraft?.let { draft ->
        StoryComposer(
            draft = draft,
            onChange = viewModel::onStoryDraftChange,
            onBackground = viewModel::selectStoryBackground,
            onMedia = viewModel::attachStoryMedia,
            onClearMedia = viewModel::clearStoryMedia,
            onDismiss = viewModel::closeStoryComposer,
            onPublish = viewModel::publishStory,
        )
    }
}

@Composable
private fun StoriesStrip(
    stories: List<Story>,
    myId: String?,
    onAdd: () -> Unit,
    onOpen: (Story) -> Unit,
) {
    // Own stories first, then the most recent authors, each author collapsed to their last story.
    val ordered = remember(stories, myId) {
        stories
            .groupBy { it.authorId }
            .map { (_, list) -> list.last() }
            .sortedWith(compareByDescending<Story> { it.authorId == myId }.thenBy { it.createdAt })
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(TomateSpacing.m),
    ) {
        item(key = "add") {
            StoryBubble(
                label = "Votre story",
                initials = "+",
                seen = true,
                onClick = onAdd,
                add = true,
            )
        }
        items(ordered, key = { it.id }) { story ->
            StoryBubble(
                label = if (story.authorId == myId) "Vous" else story.authorHandle.ifBlank { "Anonyme" },
                initials = initialsOf(story.authorDisplayName.ifBlank { story.authorHandle }),
                seen = story.viewedByMe,
                onClick = { onOpen(story) },
            )
        }
    }
}

@Composable
private fun StoryBubble(
    label: String,
    initials: String,
    seen: Boolean,
    onClick: () -> Unit,
    add: Boolean = false,
) {
    val ring = if (seen) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .border(3.dp, ring, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(
                    if (add) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (add) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Ajouter une story",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StoryViewer(
    story: Story,
    mediaUrl: String?,
    mine: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val remaining = (story.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f),
            shape = RoundedCornerShape(26.dp),
        ) {
            Box(modifier = Modifier.background(storyBrush(story.background))) {
                if (mediaUrl != null && story.mediaType == "image") {
                    RemoteStoryImage(url = mediaUrl, modifier = Modifier.fillMaxSize())
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = story.authorDisplayName.ifBlank { story.authorHandle },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "@${story.authorHandle} · ${remainingLabel(remaining)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                        if (story.pendingSync) {
                            Text(
                                text = "en attente",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        if (mine) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer ma story", tint = Color.White)
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = Color.White)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (mediaUrl != null && story.mediaType == "video") {
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mediaUrl)))
                                    }
                                }) {
                                    Text("Ouvrir la vidéo", color = Color.White)
                                }
                            }
                            if (story.body.isNotBlank()) {
                                Text(
                                    text = story.body,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = "Disparaît automatiquement 24 h après sa publication.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryComposer(
    draft: HomeViewModel.StoryDraft,
    onChange: (String) -> Unit,
    onBackground: (String) -> Unit,
    onMedia: (ByteArray, String, String, String, String) -> Unit,
    onClearMedia: () -> Unit,
    onDismiss: () -> Unit,
    onPublish: () -> Unit,
) {
    val context = LocalContext.current
    var mediaError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val isVideo = mime.startsWith("video/")
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            mediaError = "Lecture du fichier impossible."
            return@rememberLauncherForActivityResult
        }
        if (isVideo && bytes.size > 25 * 1024 * 1024) {
            mediaError = "Vidéo trop lourde : 25 Mo maximum."
            return@rememberLauncherForActivityResult
        }
        if (!isVideo) {
            val compressed = compressStoryImage(bytes)
            if (compressed == null) {
                mediaError = "Photo illisible."
                return@rememberLauncherForActivityResult
            }
            mediaError = null
            onMedia(compressed, "image", "image/jpeg", "jpg", "Photo prête (${compressed.size / 1024} Ko)")
        } else {
            val extension = when {
                mime.contains("webm") -> "webm"
                mime.contains("quicktime") -> "mov"
                else -> "mp4"
            }
            mediaError = null
            onMedia(bytes, "video", mime, extension, "Vidéo prête (${bytes.size / (1024 * 1024)} Mo)")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle story") },
        text = {
            Column {
                Text(
                    "Visible 24 h par les personnes autorisées à voir votre profil. Elle disparaît ensuite, même hors ligne.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(storyBrush(draft.background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = draft.text.ifBlank { "Votre texte…" },
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = onChange,
                    label = { Text("Texte de la story") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${draft.text.length}/${StoryRepository.MAX_STORY_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { picker.launch("*/*") }, enabled = !draft.posting) {
                        Text("Photo ou vidéo")
                    }
                    if (draft.media != null) {
                        Text(
                            text = draft.mediaLabel ?: "Média prêt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearMedia, enabled = !draft.posting) { Text("Retirer") }
                    }
                }
                mediaError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StoryRepository.BACKGROUNDS.forEach { background ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(storyBrush(background))
                                .border(
                                    width = if (draft.background == background) 3.dp else 1.dp,
                                    color = if (draft.background == background) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { onBackground(background) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPublish, enabled = (draft.text.isNotBlank() || draft.media != null) && !draft.posting) {
                Text(if (draft.posting) "Publication…" else "Publier")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }

private fun remainingLabel(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        minutes >= 60 -> "expire dans ${minutes / 60} h"
        minutes >= 1 -> "expire dans $minutes min"
        else -> "expire bientôt"
    }
}

@Composable
private fun storyBrush(background: String): Brush = when (background) {
    "ocean" -> Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF0EA5E9)))
    "forest" -> Brush.linearGradient(listOf(Color(0xFF16A34A), Color(0xFF065F46)))
    "night" -> Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF0F172A)))
    "plain" -> Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777)))
    else -> Brush.linearGradient(listOf(Color(0xFFFB923C), Color(0xFFD94A22)))
}

/** Downloads a story photo off the main thread; a failed download simply shows nothing. */
@Composable
private fun RemoteStoryImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
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
            contentDescription = "Photo de la story",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

/** Downscales to 1440 px and re-encodes as JPEG so a phone photo fits the bucket. */
private fun compressStoryImage(bytes: ByteArray): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > 1440) {
        val ratio = 1440f / longest
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    } else {
        bitmap
    }
    return ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        output.toByteArray()
    }
}
