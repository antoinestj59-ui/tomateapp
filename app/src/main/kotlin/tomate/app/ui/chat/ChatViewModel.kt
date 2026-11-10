package tomate.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    )

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

    /** Set once the conversation is left or deleted: the screen closes itself. */
    val closed = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.messaging.observeConversation(conversationId),
        container.messaging.observeMessages(conversationId),
        combine(text, sending) { t, s -> t to s },
        combine(error, container.networkMonitor.status()) { e, n -> e to n },
        // `combine` n'accepte que cinq flux typés : le reste est regroupé explicitement.
        combine(
            combine(canDelete, closing) { c, s -> c to s },
            combine(pollDialogOpen, pollQuestion, pollOptions) { o, q, opts -> Triple(o, q, opts) },
            pollError,
        ) { rights, pollState, pollFailure -> Triple(rights, pollState, pollFailure) },
    ) { conversation, allMessages, input, errNetwork, pollFields ->
        val (currentText, isSending) = input
        val (currentError, network) = errNetwork
        val (rights, pollState, pollFailure) = pollFields
        val (mayDelete, isClosing) = rights
        val (dialogOpen, question, options) = pollState
        val myId = container.session.currentUserId
        val visible = allMessages.filterNot {
            PollCodec.isVote(it.body) || ConversationExtras.isCall(it.body)
        }
        val polls = allMessages
            .mapNotNull { message ->
                PollCodec.decodePoll(message.body)?.let { poll ->
                    message.id to PollCodec.tally(allMessages, message.id, myId)
                }
            }
            .toMap()
        UiState(
            conversation = conversation,
            messages = visible,
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
            runCatching { container.sync.drain() }
        }
    }

    fun onTextChange(value: String) {
        text.value = value.take(4000)
    }

    fun send() {
        val body = text.value
        if (body.isBlank()) return
        viewModelScope.launch {
            sending.value = true
            text.value = ""
            val result = container.messaging.send(conversationId, body)
            sending.value = false
            if (result is Outcome.Failure && !result.error.isOffline) {
                error.value = result.error
            }
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
}
