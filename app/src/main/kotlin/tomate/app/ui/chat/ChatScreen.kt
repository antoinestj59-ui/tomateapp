package tomate.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.MessageBubble
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing

@Composable
private fun ConversationMenu(viewModel: ChatViewModel, canDelete: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Actions de la conversation")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Quitter la conversation") },
            onClick = {
                open = false
                viewModel.leave()
            },
        )
        if (canDelete) {
            DropdownMenuItem(
                text = { Text("Supprimer pour tout le monde") },
                onClick = {
                    open = false
                    viewModel.deleteForEveryone()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(
        factory = tomateViewModelFactory { ChatViewModel(it, conversationId) },
        key = "chat-$conversationId",
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.closed.collect { if (it) onBack() }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = buildString {
                            append(state.conversation?.title ?: "Conversation")
                            val streak = state.conversation?.streak ?: 0
                            if (streak > 0) append(" · 🔥 $streak")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.e2eeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
            actions = { ConversationMenu(viewModel = viewModel, canDelete = state.canDelete) },
        )

        if (state.error != null) {
            TomateInlineNotice(
                text = state.error!!.userMessage,
                tone = if (state.error!!.isOffline) NoticeTone.Warning else NoticeTone.Error,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
            )
        }

        if (state.messages.isEmpty()) {
            TomateEmptyState(
                title = "Aucun message",
                message = "Écrivez le premier message. Il reste stocké localement et part dès que possible.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(TomateSpacing.l),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.s),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isMine = message.senderId == state.myId,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(TomateSpacing.m),
            verticalAlignment = Alignment.Bottom,
        ) {
            TomateTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                label = "Message",
                modifier = Modifier.weight(1f),
                singleLine = false,
                minLines = 1,
                maxLines = 5,
            )
            IconButton(onClick = viewModel::send, enabled = !state.sending && state.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
            }
        }
    }
}
