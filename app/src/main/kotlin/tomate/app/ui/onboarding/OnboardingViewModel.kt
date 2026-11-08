package tomate.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.core.model.Visibility
import tomate.data.container.TomateContainer
import tomate.data.repo.ProfileRepository

/**
 * First-launch identity creation: anonymous account + handle.
 *
 * The screen explains, in plain French, that an anonymous account cannot be recovered from the
 * handle alone — this is a product decision, not a bug.
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
        val error: AppError? = null,
        val acceptedTerms: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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
        viewModelScope.launch {
            _state.value = _state.value.copy(checkingHandle = true)
            val result = container.profiles.isHandleAvailable(handle)
            _state.value = _state.value.copy(
                checkingHandle = false,
                handleAvailable = (result as? Outcome.Success)?.value,
                error = (result as? Outcome.Failure)?.error,
            )
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
        viewModelScope.launch {
            _state.value = current.copy(submitting = true, error = null)
            val display = current.displayName.ifBlank { current.handle }
            val result = container.profiles.saveProfile(
                handle = current.handle,
                displayName = display,
                bio = current.bio,
                visibility = current.visibility,
            )
            when (result) {
                is Outcome.Success -> {
                    _state.value = _state.value.copy(submitting = false)
                    container.sessionStore.putHandle(current.handle)
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
}
