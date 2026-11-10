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
import androidx.compose.material.icons.filled.Poll
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
    val call by viewModel.call.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var wantsVideo by remember { mutableStateOf(false) }

    // Micro et caméra ne sont demandés qu'au moment d'appeler, jamais au lancement de l'app.
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
            actions = {
                IconButton(onClick = { placeCall(false) }, enabled = !call.active) {
                    Icon(Icons.Default.Call, contentDescription = "Appel audio")
                }
                IconButton(onClick = { placeCall(true) }, enabled = !call.active) {
                    Icon(Icons.Default.Videocam, contentDescription = "Appel vidéo")
                }
                ConversationMenu(viewModel = viewModel, canDelete = state.canDelete)
            },
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
                    val isMine = message.senderId == state.myId
                    val poll = PollCodec.decodePoll(message.body)
                    if (poll != null) {
                        PollBubble(
                            poll = poll,
                            tally = state.polls[message.id] ?: tomate.data.extras.PollTally(emptyMap(), 0, null),
                            isMine = isMine,
                            onVote = { index -> viewModel.vote(message.id, index) },
                        )
                    } else {
                        MessageBubble(message = message, isMine = isMine)
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
                label = "Message",
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
