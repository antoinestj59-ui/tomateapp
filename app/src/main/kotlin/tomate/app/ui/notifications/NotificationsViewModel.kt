package tomate.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.data.container.TomateContainer
import tomate.data.repo.NotificationRepository
import tomate.data.repo.TomateNotification

/**
 * Écran des notifications : lecture de la table serveur `notifications` (migration 0007).
 * Aucun état n'est inventé : si la migration manque, l'écran le dit explicitement.
 */
class NotificationsViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val notifications: List<TomateNotification> = emptyList(),
        val missing: Boolean = false,
        val error: AppError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = container.notifications.load()) {
                is tomate.core.model.Outcome.Failure ->
                    _state.value = _state.value.copy(loading = false, error = result.error)
                is tomate.core.model.Outcome.Success -> {
                    val (availability, list) = result.value
                    _state.value = UiState(
                        loading = false,
                        notifications = list,
                        missing = availability == NotificationRepository.Availability.Missing,
                        error = null,
                    )
                }
            }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            container.notifications.markAllRead()
            refresh()
        }
    }

    fun markRead(notification: TomateNotification) {
        if (notification.read) return
        viewModelScope.launch {
            container.notifications.markRead(notification.id)
            _state.value = _state.value.copy(
                notifications = _state.value.notifications.map {
                    if (it.id == notification.id) it.copy(read = true) else it
                },
            )
        }
    }
}
