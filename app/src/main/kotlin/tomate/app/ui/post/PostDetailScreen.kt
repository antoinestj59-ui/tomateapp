package tomate.app.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.PostCard
import tomate.app.ui.components.formatTimestamp
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.ReportReason

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    postId: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: PostDetailViewModel = viewModel(
        factory = tomateViewModelFactory { PostDetailViewModel(it, postId) },
        key = "post-$postId",
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Publication") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Signaler cette publication") },
                        onClick = { menuOpen = false; reportDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Bloquer cet auteur") },
                        onClick = { menuOpen = false; viewModel.blockAuthor() },
                    )
                    if (state.isMine) {
                        DropdownMenuItem(
                            text = { Text("Modifier ma publication") },
                            onClick = { menuOpen = false; viewModel.startEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer ma publication") },
                            onClick = { menuOpen = false; viewModel.delete(); onBack() },
                        )
                    }
                }
            },
        )

        when {
            state.loading && state.post == null -> TomateLoadingState()
            state.post == null -> TomateEmptyState(
                title = "Publication introuvable",
                message = "Elle a peut-être été supprimée ou vous n'y avez pas accès.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(TomateSpacing.l),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
            ) {
                item {
                    val participation = state.participation
                    PostCard(
                        post = state.post!!,
                        onOpen = { },
                        onReact = viewModel::react,
                        eventGoing = participation?.going,
                        eventGoingByMe = participation?.mine ?: false,
                        onParticipate = if (participation != null) viewModel::toggleParticipation else null,
                    )
                }
                if (state.reported) {
                    item {
                        TomateInlineNotice(
                            text = "Signalement enregistré. La modération le traitera.",
                            tone = NoticeTone.Info,
                        )
                    }
                }
                if (state.blockedAuthor) {
                    item {
                        TomateInlineNotice(
                            text = "Auteur bloqué : ses contenus ne vous seront plus proposés.",
                            tone = NoticeTone.Warning,
                        )
                    }
                }
                if (state.error != null) {
                    item {
                        TomateInlineNotice(text = state.error!!.userMessage, tone = NoticeTone.Error)
                    }
                }
                item {
                    Text(
                        text = "Commentaires (${state.comments.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (state.comments.isEmpty()) {
                    item {
                        Text(
                            text = "Aucun commentaire pour le moment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.comments, key = { it.id }) { comment ->
                    TomateCard {
                        Column(modifier = Modifier.padding(TomateSpacing.m)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "@${comment.authorHandle.ifBlank { "anonyme" }}",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.width(TomateSpacing.xs))
                                Text(
                                    text = comment.createdAt.formatTimestamp(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (comment.pendingSync) {
                                    Spacer(Modifier.width(TomateSpacing.xs))
                                    Text(
                                        text = "en attente",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TomateThemeColorWarning(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(TomateSpacing.xs))
                            Text(text = comment.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.Bottom) {
                        TomateTextField(
                            value = state.commentText,
                            onValueChange = viewModel::onCommentChange,
                            label = "Ajouter un commentaire",
                            modifier = Modifier.weight(1f),
                            singleLine = false,
                            minLines = 1,
                            maxLines = 4,
                        )
                        IconButton(onClick = viewModel::sendComment, enabled = !state.sending) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer le commentaire")
                        }
                    }
                }
            }
        }
    }

    val editing by viewModel.editingFlows().collectAsStateWithLifecycle(initialValue = false to "")
    if (editing.first) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEdit,
            title = { Text("Modifier la publication") },
            text = {
                TomateTextField(
                    value = editing.second,
                    onValueChange = viewModel::onEditChange,
                    label = "Publication",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEdit) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelEdit) { Text("Annuler") }
            },
        )
    }

    if (reportDialog) {
        AlertDialog(
            onDismissRequest = { reportDialog = false },
            title = { Text("Signaler la publication") },
            text = {
                Column {
                    Text("Motif du signalement :")
                    Spacer(Modifier.height(TomateSpacing.s))
                    ReportReason.entries.forEach { reason ->
                        TextButton(
                            onClick = {
                                reportDialog = false
                                viewModel.report(reason)
                            },
                        ) { Text(reason.label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reportDialog = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun TomateThemeColorWarning() = tomate.core.designsystem.theme.TomateTheme.status.warning

@Composable
private fun EmptyRow() = Spacer(Modifier.height(0.dp))
