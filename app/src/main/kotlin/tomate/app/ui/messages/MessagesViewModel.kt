package tomate.app.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.ContactCandidate
import tomate.core.model.Conversation
import tomate.core.model.Outcome
import tomate.data.container.TomateContainer
import tomate.data.e2ee.badgeLabel

/**
 * New-conversation flow.
 *
 * Participants are chosen from real server profiles inside a searchable picker: the user never
 * has to type a comma-separated list of handles, and a typo can no longer silently create a
 * conversation nobody can read.
 */
class MessagesViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val loading: Boolean = true,
        val dialogOpen: Boolean = false,
        val title: String = "",
        val contactQuery: String = "",
        val contacts: List<ContactCandidate> = emptyList(),
        val selected: List<ContactCandidate> = emptyList(),
        val searching: Boolean = false,
        val creating: Boolean = false,
        val error: AppError? = null,
        val e2eeLabel: String = "Non chiffré de bout en bout",
    )

    private val loading = MutableStateFlow(true)
    private val dialogOpen = MutableStateFlow(false)
    private val title = MutableStateFlow("")
    private val contactQuery = MutableStateFlow("")
    private val contacts = MutableStateFlow<List<ContactCandidate>>(emptyList())
    private val selected = MutableStateFlow<List<ContactCandidate>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val creating = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private var searchJob: Job? = null

    val state: StateFlow<UiState> = combine(
        container.messaging.observeConversations(),
        combine(loading, dialogOpen) { l, d -> l to d },
        combine(title, contactQuery) { t, q -> t to q },
        combine(contacts, selected) { c, s -> c to s },
        combine(searching, creating, error) { s, c, e -> Triple(s, c, e) },
    ) { conversations, loadingPair, inputPair, contactPair, busyPair ->
        UiState(
            conversations = conversations,
            loading = loadingPair.first,
            dialogOpen = loadingPair.second,
            title = inputPair.first,
            contactQuery = inputPair.second,
            contacts = contactPair.first,
            selected = contactPair.second,
            searching = busyPair.first,
            creating = busyPair.second,
            error = busyPair.third,
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
        title.value = ""
        contactQuery.value = ""
        selected.value = emptyList()
        error.value = null
        searchContacts("")
    }

    fun closeDialog() {
        dialogOpen.value = false
        title.value = ""
        contactQuery.value = ""
        contacts.value = emptyList()
        selected.value = emptyList()
        searchJob?.cancel()
    }

    fun onTitleChange(value: String) {
        title.value = value.take(60)
    }

    /** Debounced so typing does not hit the server on every keystroke. */
    fun onContactQueryChange(value: String) {
        contactQuery.value = value.take(60)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            searchContacts(contactQuery.value)
        }
    }

    private fun searchContacts(query: String) {
        viewModelScope.launch {
            searching.value = true
            val result = container.messaging.searchContacts(query)
            when (result) {
                is Outcome.Success -> contacts.value = result.value
                is Outcome.Failure -> contacts.value = emptyList()
            }
            searching.value = false
        }
    }

    fun toggleContact(contact: ContactCandidate) {
        val current = selected.value
        selected.value = if (current.any { it.id == contact.id }) {
            current.filterNot { it.id == contact.id }
        } else {
            current + contact
        }
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val people = selected.value
            if (people.isEmpty()) {
                error.value = AppError.Validation(
                    "no_contact",
                    "Choisissez au moins un contact dans la liste.",
                )
                return@launch
            }
            creating.value = true
            val resolvedTitle = title.value.trim().ifBlank {
                if (people.size == 1) people.first().displayName
                else people.take(3).joinToString(", ") { it.displayName }
            }
            val result = container.messaging.createConversation(
                title = resolvedTitle,
                memberHandles = people.map { it.handle },
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
