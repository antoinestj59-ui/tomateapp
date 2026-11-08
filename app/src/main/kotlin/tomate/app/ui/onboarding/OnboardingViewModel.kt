package tomate.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.NetworkStatus
import tomate.core.model.Outcome
import tomate.core.model.Visibility
import tomate.data.backend.SessionManager
import tomate.data.container.TomateContainer
import tomate.data.repo.ProfileRepository

/**
 * First-launch identity creation: anonymous account + handle.
 *
 * The account is created **on the device first**. When a connection exists the same call also
 * creates the Supabase anonymous account; when there is none, a local identity is used and the
 * account is linked automatically later (see [tomate.data.account.AccountLinker]). Onboarding
 * therefore never dead-ends on the network.
 */
class OnboardingViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val handle: String = "",
        val displayName: String = "",
        val bio: String = "",
        val visibility: Visibility = Visibility.Public,
        val submitting: Boolean = false,
        val handleAvailable: Boolean? = null,
        val checkingHandle: Boolean = false,
        val verificationDeferred: Boolean = false,
        val offline: Boolean = false,
        val error: AppError? = null,
        val acceptedTerms: Boolean = false,
        val localAccount: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Debounces the availability request: one call per pause, not one per keystroke. */
    private var handleJob: Job? = null

    init {
        viewModelScope.launch {
            container.networkMonitor.status().collect { status ->
                val offline = status == NetworkStatus.Offline
                _state.value = _state.value.copy(
                    offline = offline,
                    // A deferred check becomes meaningful again when the network returns.
                    verificationDeferred = if (offline) _state.value.verificationDeferred else false,
                )
            }
        }
    }

    fun onHandleChange(value: String) {
        val normalised = ProfileRepository.normaliseHandle(value).take(20)
        _state.value = _state.value.copy(handle = normalised, handleAvailable = null, error = null)
        if (ProfileRepository.isValidHandle(normalised)) {
            checkHandle(normalised)
        }
    }

    fun onDisplayNameChange(value: String) {
        _state.value = _state.value.copy(displayName = value.take(40), error = null)
    }

    fun onBioChange(value: String) {
        _state.value = _state.value.copy(bio = value.take(280))
    }

    fun onVisibilityChange(visibility: Visibility) {
        _state.value = _state.value.copy(visibility = visibility)
    }

    fun onTermsChange(accepted: Boolean) {
        _state.value = _state.value.copy(acceptedTerms = accepted)
    }

    private fun checkHandle(handle: String) {
        handleJob?.cancel()
        if (_state.value.offline) {
            // No network: do not invent an answer, and do not show a fake error either.
            _state.value = _state.value.copy(
                checkingHandle = false,
                handleAvailable = null,
                verificationDeferred = true,
            )
            return
        }
        handleJob = viewModelScope.launch {
            delay(HANDLE_CHECK_DEBOUNCE_MS)
            _state.value = _state.value.copy(checkingHandle = true)
            val result = container.profiles.isHandleAvailable(handle)
            when (result) {
                is Outcome.Success -> _state.value = _state.value.copy(
                    checkingHandle = false,
                    handleAvailable = result.value,
                    verificationDeferred = false,
                )
                is Outcome.Failure -> _state.value = _state.value.copy(
                    checkingHandle = false,
                    handleAvailable = null,
                    verificationDeferred = result.error.isOffline,
                    error = if (result.error.isOffline) null else result.error,
                )
            }
        }
    }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (!current.acceptedTerms) {
            _state.value = current.copy(
                error = AppError.Validation("terms", "Vous devez accepter les règles de base pour continuer."),
            )
            return
        }
        if (current.handle.isBlank()) {
            _state.value = current.copy(
                error = AppError.Validation("handle", "Choisissez un pseudo pour créer votre compte."),
            )
            return
        }
        viewModelScope.launch {
            _state.value = current.copy(submitting = true, error = null)
            if (current.offline) {
                // Deterministic offline path: no signup attempt, no timeout, local identity now.
                container.session.startLocalSession()
            }
            val display = current.displayName.ifBlank { current.handle }
            val result = container.profiles.saveProfile(
                handle = current.handle,
                displayName = display,
                bio = current.bio,
                visibility = current.visibility,
            )
            when (result) {
                is Outcome.Success -> {
                    val isLocal = SessionManager.isLocalId(result.value.id)
                    _state.value = _state.value.copy(submitting = false, localAccount = isLocal)
                    container.sessionStore.putHandle(current.handle)
                    if (isLocal) {
                        // Make the local identity explicit so the linker picks it up later.
                        container.session.startLocalSession()
                    }
                    onSuccess()
                }
                is Outcome.Failure -> {
                    _state.value = _state.value.copy(submitting = false, error = result.error)
                }
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val HANDLE_CHECK_DEBOUNCE_MS = 450L
    }
}
