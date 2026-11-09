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

class ChatViewModel(
    private val container: TomateContainer,
    conversationId: String,
) : ViewModel() {

    data class UiState(
        val conversation: Conversation? = null,
        val messages: List<Message> = emptyList(),
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

    /** Set once the conversation is left or deleted: the screen closes itself. */
    val closed = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.messaging.observeConversation(conversationId),
        container.messaging.observeMessages(conversationId),
        combine(text, sending) { t, s -> t to s },
        combine(error, container.networkMonitor.status()) { e, n -> e to n },
        combine(canDelete, closing) { c, s -> c to s },
    ) { conversation, messages, input, errNetwork, rights ->
        val (currentText, isSending) = input
        val (currentError, network) = errNetwork
        val (mayDelete, isClosing) = rights
        UiState(
            conversation = conversation,
            messages = messages,
            text = currentText,
            sending = isSending,
            error = currentError ?: if (network == tomate.core.model.NetworkStatus.Offline && messages.any { it.deliveryState == tomate.core.model.MessageDeliveryState.Pending }) {
                AppError.Offline("Les messages en attente partiront au retour du réseau.")
            } else {
                null
            },
            myId = container.session.currentUserId,
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
