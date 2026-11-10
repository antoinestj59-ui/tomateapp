package tomate.app.ui.chat

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.MessageBubble
import tomate.app.ui.components.PollBubble
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.TomateEmptyState
import tomate.data.extras.PollCodec
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing

@Composable
private fun ConversationMenu(viewModel: ChatViewModel, canDelete: Boolean, isGroup: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Actions de la conversation")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (isGroup) {
            DropdownMenuItem(
                text = { Text("Renommer le groupe") },
                onClick = {
                    open = false
                    viewModel.openRename()
                },
            )
        }
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
    val call by viewModel.call.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var wantsVideo by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startCall(wantsVideo)
        }
    }

    fun placeCall(video: Boolean) {
        wantsVideo = video
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (needed.isEmpty()) viewModel.startCall(video) else permissions.launch(needed.toTypedArray())
    }

    LaunchedEffect(Unit) {
        viewModel.closed.collect { if (it) onBack() }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && state.searchQuery.isBlank()) {
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
            actions = {
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Filled.Search, contentDescription = "Rechercher dans la conversation")
                }
                IconButton(onClick = { placeCall(false) }, enabled = !call.active) {
                    Icon(Icons.Default.Call, contentDescription = "Appel audio")
                }
                IconButton(onClick = { placeCall(true) }, enabled = !call.active) {
                    Icon(Icons.Default.Videocam, contentDescription = "Appel vidéo")
                }
                ConversationMenu(
                    viewModel = viewModel,
                    canDelete = state.canDelete,
                    isGroup = state.conversation?.isGroup == true,
                )
            },
        )

        if (searchOpen) {
            TomateTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                label = "Rechercher dans la conversation",
                modifier = Modifier.padding(horizontal = TomateSpacing.l),
                singleLine = true,
            )
            Spacer(Modifier.height(TomateSpacing.xs))
        }

        if (state.error != null) {
            TomateInlineNotice(
                text = state.error!!.userMessage,
                tone = if (state.error!!.isOffline) NoticeTone.Warning else NoticeTone.Error,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
                onDismiss = viewModel::dismissError,
            )
        }

        // Bandeau de réponse : cite le message d'origine, annulable.
        if (state.replyToId != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
            ) {
                Row(
                    modifier = Modifier.padding(TomateSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Réponse : ${state.replyToPreview?.take(100) ?: "…"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = viewModel::cancelReply, modifier = androidx.compose.ui.Modifier) {
                        Icon(Icons.Filled.Close, contentDescription = "Annuler la réponse")
                    }
                }
            }
        }

        if (state.messages.isEmpty()) {
            TomateEmptyState(
                title = if (state.searchQuery.isBlank()) "Aucun message" else "Aucun résultat",
                message = if (state.searchQuery.isBlank()) {
                    "Écrivez le premier message. Il reste stocké localement et part dès que possible."
                } else {
                    "Aucun message ne contient « ${state.searchQuery} » dans cette conversation."
                },
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
                    val isMine = message.senderId == state.myId
                    val poll = PollCodec.decodePoll(message.body)
                    val byId = state.messages.associateBy { it.id }
                    val replyPreview = message.replyToId?.let { byId[it]?.body?.take(120) }
                    if (poll != null) {
                        PollBubble(
                            poll = poll,
                            tally = state.polls[message.id] ?: tomate.data.extras.PollTally(emptyMap(), 0, null),
                            isMine = isMine,
                            onVote = { index -> viewModel.vote(message.id, index) },
                        )
                    } else {
                        Column {
                            MessageBubble(
                                message = message,
                                isMine = isMine,
                                replyPreview = replyPreview,
                                onReply = { viewModel.replyTo(message) },
                            )
                            val tally = state.reactions[message.id].orEmpty()
                            if (tally.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    tally.forEach { (emoji, count) ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { viewModel.react(message.id, emoji) },
                                            label = { Text("$emoji $count") },
                                        )
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    ChatViewModel.QUICK_EMOJIS.take(3).forEach { emoji ->
                                        TextButton(onClick = { viewModel.react(message.id, emoji) }) {
                                            Text(emoji)
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                label = if (state.replyToId != null) "Répondre…" else "Message",
                modifier = Modifier.weight(1f),
                singleLine = false,
                minLines = 1,
                maxLines = 5,
            )
            IconButton(onClick = viewModel::openPollDialog, enabled = !state.sending) {
                Icon(Icons.Default.Poll, contentDescription = "Créer un sondage")
            }
            IconButton(onClick = viewModel::send, enabled = !state.sending && state.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
            }
        }

        if (state.pollDialogOpen) {
            PollCreationDialog(state = state, viewModel = viewModel)
        }

        if (state.renameDialogOpen) {
            AlertDialog(
                onDismissRequest = viewModel::closeRename,
                title = { Text("Renommer le groupe") },
                text = {
                    TomateTextField(
                        value = state.renameText,
                        onValueChange = viewModel::onRenameChange,
                        label = "Nom du groupe",
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmRename) { Text("Renommer") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::closeRename) { Text("Annuler") }
                },
            )
        }

        // Le contexte OpenGL n'est créé qu'à partir du premier appel, pas à l'ouverture du fil.
        if (call.stage != tomate.app.call.CallEngine.Stage.Idle) CallOverlay(
            snapshot = call,
            eglBase = viewModel.eglBase,
            onAccept = viewModel::acceptCall,
            onDecline = viewModel::declineCall,
            onHangUp = viewModel::hangUpCall,
            onToggleMute = viewModel::toggleMute,
            onToggleCamera = viewModel::toggleCamera,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onDismiss = viewModel::dismissCall,
        )
    }
}

@Composable
private fun PollCreationDialog(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closePollDialog,
        title = { Text("Nouveau sondage") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.s),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TomateTextField(
                    value = state.pollQuestion,
                    onValueChange = viewModel::onPollQuestionChange,
                    label = "Question",
                    singleLine = true,
                )
                state.pollOptions.forEachIndexed { index, option ->
                    TomateTextField(
                        value = option,
                        onValueChange = { viewModel.onPollOptionChange(index, it) },
                        label = "Option ${index + 1}",
                        singleLine = true,
                    )
                }
                if (state.pollOptions.size < 3) {
                    TextButton(onClick = viewModel::addPollOption) { Text("Ajouter une option") }
                }
                if (state.pollError != null) {
                    TomateInlineNotice(text = state.pollError, tone = NoticeTone.Error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::createPoll, enabled = !state.sending) { Text("Créer") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closePollDialog) { Text("Annuler") }
        },
    )
}
