package tomate.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.ConversationRow
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateAvatar
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.ContactCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenConversation: (String) -> Unit,
    openComposerRequest: Boolean = false,
    onComposerRequestConsumed: () -> Unit = {},
    viewModel: MessagesViewModel = viewModel(factory = tomateViewModelFactory { MessagesViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ConversationFilter.All) }

    LaunchedEffect(openComposerRequest) {
        if (openComposerRequest) {
            viewModel.openDialog()
            onComposerRequestConsumed()
        }
    }

    val visible = remember(state.conversations, filter) {
        when (filter) {
            ConversationFilter.All -> state.conversations
            ConversationFilter.Groups -> state.conversations.filter { it.isGroup }
            ConversationFilter.Direct -> state.conversations.filter { !it.isGroup }
            ConversationFilter.Unread -> state.conversations.filter { it.unreadCount > 0 }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages", fontWeight = FontWeight.Bold) },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = TomateSpacing.s)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = viewModel::openDialog),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Nouvelle conversation",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openDialog,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.ChatBubbleOutline, contentDescription = "Nouvelle conversation")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TomateInlineNotice(
                text = "Chiffrement : ${state.e2eeLabel} (les messages 1-1 échangés avec une clé appairée sont chiffrés).",
                tone = NoticeTone.Info,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
            )
            if (state.error != null) {
                TomateInlineNotice(
                    text = state.error!!.userMessage,
                    tone = NoticeTone.Error,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s),
            ) {
                items(ConversationFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    )
                }
            }

            when {
                state.loading -> TomateLoadingState()
                state.conversations.isEmpty() -> TomateEmptyState(
                    icon = Icons.Filled.ChatBubbleOutline,
                    title = "Aucune conversation",
                    message = "Choisissez un contact : la conversation reste lisible hors ligne et vos messages en attente partent au retour du réseau.",
                    actionLabel = "Nouvelle conversation",
                    onAction = viewModel::openDialog,
                )
                visible.isEmpty() -> Text(
                    text = "Aucune conversation dans « ${filter.label} ».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(TomateSpacing.l),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = TomateSpacing.xxl),
                ) {
                    items(visible, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { onOpenConversation(conversation.id) },
                        )
                    }
                }
            }
        }
    }

    if (state.dialogOpen) {
        NewConversationDialog(
            state = state,
            onQueryChange = viewModel::onContactQueryChange,
            onTitleChange = viewModel::onTitleChange,
            onToggle = viewModel::toggleContact,
            onDismiss = viewModel::closeDialog,
            onCreate = { viewModel.create(onOpenConversation) },
        )
    }
}

/**
 * Participant picker. The list contains server profiles only and the selected people are shown
 * as chips, so creating a group is a selection instead of a syntax the user has to remember.
 */
@Composable
private fun NewConversationDialog(
    state: MessagesViewModel.UiState,
    onQueryChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onToggle: (ContactCandidate) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle conversation") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (state.selected.isNotEmpty()) {
                    Text(
                        text = "${state.selected.size} contact(s) sélectionné(s)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s),
                    ) {
                        state.selected.take(4).forEach { person ->
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable { onToggle(person) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "@${person.handle}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "×",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(TomateSpacing.m))
                }
                TomateTextField(
                    value = state.contactQuery,
                    onValueChange = onQueryChange,
                    label = "Rechercher une personne",
                    supportingText = "Pseudo ou nom. Choisissez dans la liste.",
                )
                Spacer(Modifier.height(TomateSpacing.s))
                when {
                    state.searching -> Box(
                        modifier = Modifier.fillMaxWidth().padding(TomateSpacing.l),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }
                    state.contacts.isEmpty() -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = TomateSpacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.PersonSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(TomateSpacing.s))
                        Text(
                            text = if (state.contactQuery.isBlank()) {
                                "Aucun autre profil sur ce serveur pour l'instant."
                            } else {
                                "Aucun profil ne correspond à « ${state.contactQuery} »."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.contacts, key = { it.id }) { contact ->
                            val picked = state.selected.any { it.id == contact.id }
                            ContactPickerRow(
                                contact = contact,
                                picked = picked,
                                onClick = { onToggle(contact) },
                            )
                        }
                    }
                }
                if (state.selected.size == 1) {
                    Spacer(Modifier.height(TomateSpacing.m))
                    TomateTextField(
                        value = state.title,
                        onValueChange = onTitleChange,
                        label = "Titre (facultatif)",
                        supportingText = "Par défaut : le nom du contact.",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCreate,
                enabled = !state.creating && state.selected.isNotEmpty(),
            ) { Text(if (state.creating) "Création…" else "Créer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ContactPickerRow(
    contact: ContactCandidate,
    picked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.s, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TomateAvatar(label = contact.displayName.ifBlank { contact.handle }, size = 36)
        Spacer(Modifier.width(TomateSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName.ifBlank { contact.handle },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "@${contact.handle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (picked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Sélectionné",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Filtres de conversation. Chaque option correspond à un champ réel de [Conversation] :
 * aucune catégorie inventée (pas de « Appels » sans historique d'appels stocké).
 */
private enum class ConversationFilter(val label: String) {
    All("Toutes"),
    Direct("Privées"),
    Groups("Groupes"),
    Unread("Non lues"),
}
