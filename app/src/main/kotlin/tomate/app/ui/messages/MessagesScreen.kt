package tomate.app.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.ConversationRow
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenConversation: (String) -> Unit,
    viewModel: MessagesViewModel = viewModel(factory = tomateViewModelFactory { MessagesViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Messages") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openDialog,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nouvelle conversation") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TomateInlineNotice(
                text = "Chiffrement : ${state.e2eeLabel}. Le chiffrement de bout en bout n'est pas encore opérationnel.",
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
            if (state.error != null) {
                TomateInlineNotice(
                    text = state.error!!.userMessage,
                    tone = NoticeTone.Error,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                )
            }
            when {
                state.loading -> TomateLoadingState()
                state.conversations.isEmpty() -> TomateEmptyState(
                    icon = Icons.Filled.ChatBubbleOutline,
                    title = "Aucune conversation",
                    message = "Créez une conversation : elle reste lisible hors ligne et vos messages en attente partent au retour du réseau.",
                    actionLabel = "Nouvelle conversation",
                    onAction = viewModel::openDialog,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(TomateSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
                ) {
                    items(state.conversations, key = { it.id }) { conversation ->
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
        AlertDialog(
            onDismissRequest = viewModel::closeDialog,
            title = { Text("Nouvelle conversation") },
            text = {
                Column {
                    TomateTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = "Titre (ou destinataire)",
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.members,
                        onValueChange = viewModel::onMembersChange,
                        label = "Pseudos des participants",
                        supportingText = "Séparés par des virgules. Laissez vide pour une conversation individuelle.",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.create(onOpenConversation) },
                    enabled = !state.creating && state.title.isNotBlank(),
                ) { Text(if (state.creating) "Création…" else "Créer") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeDialog) { Text("Annuler") }
            },
        )
    }
}
