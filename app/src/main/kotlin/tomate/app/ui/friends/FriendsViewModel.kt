package tomate.app.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.container.TomateContainer
import tomate.data.repo.FriendRequest
import tomate.data.repo.FriendsAvailability

/**
 * Demandes d'amis réelles (migration 0012).
 *
 * Aucun état n'est simulé : si la table manque, l'écran l'annonce ; si la réponse est refusée,
 * le serveur a la dernière parole ; si le réseau est absent, le message le dit.
 */
class FriendsViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val availability: FriendsAvailability = FriendsAvailability.Ready,
        val incoming: List<FriendRequest> = emptyList(),
        val outgoing: List<FriendRequest> = emptyList(),
        val friends: List<FriendRequest> = emptyList(),
        val busyId: String? = null,
        val error: AppError? = null,
        val notice: String? = null,
        val questionFor: FriendRequest? = null,
        val question: String = DEFAULT_QUESTION,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = container.friends.load()) {
                is Outcome.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
                is Outcome.Success -> {
                    val all = result.value.requests
                    _state.value = _state.value.copy(
                        loading = false,
                        availability = result.value.availability,
                        incoming = all.filter { !it.outgoing && it.pending },
                        outgoing = all.filter { it.outgoing && it.pending },
                        friends = all.filter { it.accepted }.distinctBy { it.otherId },
                        error = null,
                    )
                }
            }
        }
    }

    fun accept(request: FriendRequest) = respond(request, accept = true)

    fun refuse(request: FriendRequest) = respond(request, accept = false)

    private fun respond(request: FriendRequest, accept: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = request.id, error = null, notice = null)
            when (val result = container.friends.respond(request.id, accept)) {
                is Outcome.Success -> {
                    _state.value = _state.value.copy(
                        busyId = null,
                        notice = if (accept) {
                            "${request.otherLabel} fait maintenant partie de tes amis."
                        } else {
                            "Demande de ${request.otherLabel} refusée."
                        },
                    )
                    refresh()
                }
                is Outcome.Failure -> _state.value = _state.value.copy(busyId = null, error = result.error)
            }
        }
    }

    fun cancel(request: FriendRequest) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = request.id, error = null, notice = null)
            when (val result = container.friends.cancel(request.id)) {
                is Outcome.Success -> refresh()
                is Outcome.Failure -> _state.value = _state.value.copy(busyId = null, error = result.error)
            }
        }
    }

    /** Ouvre la question « pourquoi ? » : c'est un vrai message, pas une notification inventée. */
    fun askWhy(request: FriendRequest) {
        _state.value = _state.value.copy(questionFor = request, question = DEFAULT_QUESTION, error = null)
    }

    fun onQuestionChange(text: String) {
        _state.value = _state.value.copy(question = text.take(500))
    }

    fun closeQuestion() {
        _state.value = _state.value.copy(questionFor = null, question = DEFAULT_QUESTION)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Envoie la question dans une conversation réelle avec la personne.
     * Si la conversation ne peut pas être créée, aucune fausse confirmation n'est affichée.
     */
    fun sendQuestion(onOpenConversation: (String) -> Unit) {
        val request = _state.value.questionFor ?: return
        val text = _state.value.question.trim()
        if (text.isBlank()) {
            _state.value = _state.value.copy(
                error = AppError.Validation("empty_question", "Écrivez votre question avant de l'envoyer."),
            )
            return
        }
        if (request.otherHandle.isBlank()) {
            _state.value = _state.value.copy(
                error = AppError.Validation("unknown_handle", "Ce contact n'a pas de pseudo public : impossible de lui écrire."),
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = request.id, error = null)
            when (
                val conversation = container.messaging.createConversation(
                    title = request.otherLabel,
                    memberHandles = listOf(request.otherHandle),
                )
            ) {
                is Outcome.Failure -> _state.value = _state.value.copy(busyId = null, error = conversation.error)
                is Outcome.Success -> {
                    val sent = container.messaging.send(conversation.value.id, text)
                    _state.value = _state.value.copy(
                        busyId = null,
                        questionFor = null,
                        question = DEFAULT_QUESTION,
                        notice = if (sent is Outcome.Success) {
                            "Question envoyée à ${request.otherLabel}."
                        } else {
                            "La conversation est ouverte : le message partira dès que possible."
                        },
                    )
                    onOpenConversation(conversation.value.id)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_QUESTION = "Pourquoi veux-tu m'ajouter en ami ?"
    }
}
