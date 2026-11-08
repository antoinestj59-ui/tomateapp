package tomate.app.ui.messages

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
import tomate.core.model.Outcome
import tomate.data.container.TomateContainer
import tomate.data.e2ee.badgeLabel

class MessagesViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val loading: Boolean = true,
        val dialogOpen: Boolean = false,
        val title: String = "",
        val members: String = "",
        val creating: Boolean = false,
        val error: AppError? = null,
        val e2eeLabel: String = "Non chiffré de bout en bout",
    )

    private val loading = MutableStateFlow(true)
    private val dialogOpen = MutableStateFlow(false)
    private val title = MutableStateFlow("")
    private val members = MutableStateFlow("")
    private val creating = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    val state: StateFlow<UiState> = combine(
        container.messaging.observeConversations(),
        combine(loading, dialogOpen) { l, d -> l to d },
        combine(title, members) { t, m -> t to m },
        combine(creating, error) { c, e -> c to e },
    ) { conversations, loadingPair, inputPair, actionPair ->
        UiState(
            conversations = conversations,
            loading = loadingPair.first,
            dialogOpen = loadingPair.second,
            title = inputPair.first,
            members = inputPair.second,
            creating = actionPair.first,
            error = actionPair.second,
            e2eeLabel = container.e2ee.status.badgeLabel(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            val result = container.messaging.refreshConversations()
            if (result is Outcome.Failure) error.value = result.error
            runCatching { container.sync.drain() }
            loading.value = false
        }
    }

    fun openDialog() {
        dialogOpen.value = true
    }

    fun closeDialog() {
        dialogOpen.value = false
        title.value = ""
        members.value = ""
    }

    fun onTitleChange(value: String) {
        title.value = value.take(60)
    }

    fun onMembersChange(value: String) {
        members.value = value.take(200)
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            creating.value = true
            val handles = members.value
                .split(',', ' ')
                .map { it.trim().removePrefix("@") }
                .filter { it.isNotBlank() }
            val result = container.messaging.createConversation(
                title = title.value,
                memberHandles = handles,
            )
            creating.value = false
            when (result) {
                is Outcome.Success -> {
                    closeDialog()
                    onCreated(result.value.id)
                }
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun dismissError() {
        error.value = null
    }
}
