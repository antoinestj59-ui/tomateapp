package tomate.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tomate.app.call.CallController
import tomate.app.call.CallEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.Conversation
import tomate.core.model.Message
import tomate.core.model.Outcome
import tomate.data.container.TomateContainer
import tomate.data.e2ee.badgeLabel
import tomate.data.extras.ConversationExtras
import tomate.data.extras.PollCodec
import tomate.data.extras.PollTally

class ChatViewModel(
    private val container: TomateContainer,
    conversationId: String,
) : ViewModel() {

    data class UiState(
        val conversation: Conversation? = null,
        /** Messages affichés : les votes et la signalisation ne sont jamais rendus en texte. */
        val messages: List<Message> = emptyList(),
        /** Dépouillement par identifiant de message-sondage, recalculé à chaque nouveau vote. */
        val polls: Map<String, PollTally> = emptyMap(),
        val pollDialogOpen: Boolean = false,
        val pollQuestion: String = "",
        val pollOptions: List<String> = listOf("", ""),
        val pollError: String? = null,
        val text: String = "",
        val sending: Boolean = false,
        val error: AppError? = null,
        val myId: String? = null,
        val e2eeLabel: String = "Non chiffré de bout en bout",
        val canDelete: Boolean = false,
        val closing: Boolean = false,
        /** Réponse en cours : identifiant du message cité, `null` quand aucune. */
        val replyToId: String? = null,
        val replyToPreview: String? = null,
        /** Recherche locale dans la conversation. */
        val searchQuery: String = "",
        /** Réactions par message : emoji -> nombre. */
        val reactions: Map<String, Map<String, Int>> = emptyMap(),
        val renameDialogOpen: Boolean = false,
        val renameText: String = "",
    )

    companion object {
        val QUICK_EMOJIS = listOf("🍅", "❤️", "👍", "😂", "😮", "🙏")
    }

    private val conversationId = conversationId
    private val text = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val canDelete = MutableStateFlow(false)
    private val closing = MutableStateFlow(false)
    private val pollDialogOpen = MutableStateFlow(false)
    private val pollQuestion = MutableStateFlow("")
    private val pollOptions = MutableStateFlow(listOf("", ""))
    private val pollError = MutableStateFlow<String?>(null)
    private val replyToId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val reactions = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    private val renameDialogOpen = MutableStateFlow(false)
    private val renameText = MutableStateFlow("")

    /** Set once the conversation is left or deleted: the screen closes itself. */
    val closed = MutableStateFlow(false)

    private val calls = CallController(container, conversationId, viewModelScope)

    /** État de l'appel en cours (ou d'un appel entrant) pour cette conversation. */
    val call: kotlinx.coroutines.flow.StateFlow<CallEngine.Snapshot> get() = calls.state

    /** Contexte OpenGL partagé par les rendus vidéo de l'appel. */
    val eglBase: org.webrtc.EglBase get() = calls.engine.eglBase

    val state: StateFlow<UiState> = combine(
        container.messaging.observeConversation(conversationId),
        container.messaging.observeMessages(conversationId),
        combine(text, sending) { t, s -> t to s },
        combine(error, container.networkMonitor.status()) { e, n -> e to n },
        combine(
            combine(canDelete, closing) { c, s -> c to s },
            combine(pollDialogOpen, pollQuestion, pollOptions) { o, q, opts -> Triple(o, q, opts) },
            combine(replyToId, searchQuery) { r, q -> r to q },
            combine(reactions, renameDialogOpen, renameText) { reacts, dialog, name -> Triple(reacts, dialog, name) },
            pollError,
        ) { rights, pollState, replySearch, renameState, pollFailure ->
            listOf(rights, pollState, replySearch, renameState, pollFailure)
        },
    ) { conversation, allMessages, input, errNetwork, misc ->
        val (currentText, isSending) = input
        val (currentError, network) = errNetwork
        @Suppress("UNCHECKED_CAST")
        val rights = misc[0] as Pair<Boolean, Boolean>
        @Suppress("UNCHECKED_CAST")
        val pollState = misc[1] as Triple<Boolean, String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val replySearch = misc[2] as Pair<String?, String>
        @Suppress("UNCHECKED_CAST")
        val renameState = misc[3] as Triple<Map<String, Map<String, Int>>, Boolean, String>
        val pollFailure = misc[4] as String?
        val (mayDelete, isClosing) = rights
        val (dialogOpen, question, options) = pollState
        val (replyId, query) = replySearch
        val (reacts, renameOpen, renameValue) = renameState
        val myId = container.session.currentUserId
        val visible = allMessages.filterNot {
            PollCodec.isVote(it.body) || ConversationExtras.isCall(it.body)
        }
        val filtered = if (query.isBlank()) visible
        else visible.filter { it.body.contains(query.trim(), ignoreCase = true) }
        val polls = allMessages
            .mapNotNull { message ->
                PollCodec.decodePoll(message.body)?.let {
                    message.id to PollCodec.tally(allMessages, message.id, myId)
                }
            }
            .toMap()
        val replyPreview = replyId?.let { id -> visible.firstOrNull { it.id == id }?.body?.take(120) }
        UiState(
            conversation = conversation,
            messages = filtered,
            polls = polls,
            pollDialogOpen = dialogOpen,
            pollQuestion = question,
            pollOptions = options,
            pollError = pollFailure,
            text = currentText,
            sending = isSending,
            error = currentError ?: if (network == tomate.core.model.NetworkStatus.Offline && visible.any { it.deliveryState == tomate.core.model.MessageDeliveryState.Pending }) {
                AppError.Offline("Les messages en attente partiront au retour du réseau.")
            } else {
                null
            },
            myId = myId,
            e2eeLabel = container.e2ee.status.badgeLabel(),
            canDelete = mayDelete,
            closing = isClosing,
            replyToId = replyId,
            replyToPreview = replyPreview,
            searchQuery = query,
            reactions = reacts,
            renameDialogOpen = renameOpen,
            renameText = renameValue,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
        viewModelScope.launch {
            canDelete.value = container.messaging.isConversationCreator(conversationId)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            container.messaging.refreshMessages(conversationId)
            container.messaging.markConversationRead(conversationId)
            refreshReactions()
            runCatching { container.sync.drain() }
        }
    }

    private suspend fun refreshReactions() {
        val current = state.value.messages.map { it.id }
        val result = container.messaging.fetchMessageReactions(current)
        if (result is Outcome.Success) reactions.value = result.value
    }

    fun onTextChange(value: String) {
        text.value = value.take(4000)
    }

    fun onSearchChange(value: String) {
        searchQuery.value = value.take(80)
    }

    fun replyTo(message: Message) {
        replyToId.value = message.id
    }

    fun cancelReply() {
        replyToId.value = null
    }

    fun send() {
        val body = text.value
        if (body.isBlank()) return
        val reply = replyToId.value
        viewModelScope.launch {
            sending.value = true
            text.value = ""
            replyToId.value = null
            val result = container.messaging.send(conversationId, body, replyToId = reply)
            sending.value = false
            if (result is Outcome.Failure && !result.error.isOffline) {
                error.value = result.error
            }
            refreshReactions()
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch {
            val mine = state.value.reactions[messageId]?.containsKey(emoji) == true &&
                state.value.myId != null
            // Optimiste : l'API dira si la migration 0010 manque, sans rien inventer.
            val result = container.messaging.toggleMessageReaction(messageId, emoji, currentlyReacted = false)
            if (result is Outcome.Failure) {
                error.value = result.error
            } else {
                refreshReactions()
            }
        }
    }

    fun openRename() {
        renameText.value = state.value.conversation?.title.orEmpty()
        renameDialogOpen.value = true
    }

    fun onRenameChange(value: String) {
        renameText.value = value.take(80)
    }

    fun closeRename() {
        renameDialogOpen.value = false
    }

    fun confirmRename() {
        viewModelScope.launch {
            val result = container.messaging.renameConversation(conversationId, renameText.value)
            renameDialogOpen.value = false
            if (result is Outcome.Failure) error.value = result.error
        }
    }

    fun openPollDialog() {
        pollError.value = null
        pollDialogOpen.value = true
    }

    fun closePollDialog() {
        pollDialogOpen.value = false
        pollError.value = null
    }

    fun onPollQuestionChange(value: String) {
        pollQuestion.value = value.take(200)
    }

    fun onPollOptionChange(index: Int, value: String) {
        pollOptions.value = pollOptions.value.toMutableList().also { it[index] = value.take(120) }
    }

    /** Trois options au maximum côté UI, six possibles par le protocole. */
    fun addPollOption() {
        if (pollOptions.value.size >= 3) return
        pollOptions.value = pollOptions.value + ""
    }

    fun createPoll() {
        val body = PollCodec.encodePoll(pollQuestion.value, pollOptions.value)
        if (body == null) {
            pollError.value = "Il faut une question et au moins deux options."
            return
        }
        viewModelScope.launch {
            sending.value = true
            val preview = "📊 " + pollQuestion.value.trim()
            val result = container.messaging.send(conversationId, body, preview = preview)
            sending.value = false
            if (result is Outcome.Failure) {
                pollError.value = result.error.userMessage
            } else {
                pollQuestion.value = ""
                pollOptions.value = listOf("", "")
                pollDialogOpen.value = false
            }
        }
    }

    /**
     * Un vote est un message ordinaire portant le protocole : il part par la même file locale
     * que le reste, donc il survit à une coupure réseau. Il ne modifie pas l'aperçu de la
     * conversation, qui doit rester lisible.
     */
    fun vote(pollId: String, option: Int) {
        val body = PollCodec.encodeVote(pollId, option) ?: return
        viewModelScope.launch {
            val result = container.messaging.send(conversationId, body, updatePreview = false)
            if (result is Outcome.Failure && !result.error.isOffline) {
                error.value = result.error
            }
        }
    }

    fun leave() {
        viewModelScope.launch {
            closing.value = true
            val result = container.messaging.leaveConversation(conversationId)
            closing.value = false
            if (result is Outcome.Failure) error.value = result.error else closed.value = true
        }
    }

    fun deleteForEveryone() {
        viewModelScope.launch {
            closing.value = true
            val result = container.messaging.deleteConversation(conversationId)
            closing.value = false
            if (result is Outcome.Failure) error.value = result.error else closed.value = true
        }
    }

    fun dismissError() {
        error.value = null
    }

    // ------------------------------------------------------------------- appels

    fun startCall(video: Boolean) = calls.call(video)

    fun acceptCall() = calls.accept()

    fun declineCall() = calls.decline()

    fun hangUpCall() = calls.hangUp()

    fun toggleMute() {
        val snapshot = call.value
        calls.setMuted(!snapshot.micMuted)
    }

    fun toggleCamera() {
        val snapshot = call.value
        calls.setCameraOff(!snapshot.cameraOff)
    }

    fun toggleSpeaker() = calls.setSpeaker(!calls.engine.isSpeakerOn)

    fun dismissCall() = calls.clear()

    override fun onCleared() {
        calls.release()
        super.onCleared()
    }
}
