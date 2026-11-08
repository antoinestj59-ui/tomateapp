package tomate.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.BlockedUser
import tomate.core.model.Outcome
import tomate.core.model.Profile
import tomate.core.model.Visibility
import tomate.data.container.TomateContainer
import tomate.data.e2ee.badgeLabel
import tomate.data.security.RecoverySecret

class ProfileViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val profile: Profile? = null,
        val editing: Boolean = false,
        val editHandle: String = "",
        val editDisplayName: String = "",
        val editBio: String = "",
        val editVisibility: Visibility = Visibility.Public,
        val saving: Boolean = false,
        val error: AppError? = null,
        val recoveryCode: String? = null,
        val recoveryStatus: String = "Code de récupération : non généré.",
        val blocked: List<BlockedUser> = emptyList(),
        val e2eeLabel: String = "Non chiffré de bout en bout",
        val userId: String? = null,
        val pendingOperations: Int = 0,
    )

    private data class EditState(
        val open: Boolean,
        val handle: String,
        val name: String,
        val bio: String,
        val visibility: Visibility,
    )

    private data class ActionState(
        val saving: Boolean,
        val error: AppError?,
        val code: String?,
        val status: String,
    )

    private val editing = MutableStateFlow(false)
    private val editHandle = MutableStateFlow("")
    private val editDisplayName = MutableStateFlow("")
    private val editBio = MutableStateFlow("")
    private val editVisibility = MutableStateFlow(Visibility.Public)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val recoveryCode = MutableStateFlow<String?>(null)
    private val recoveryStatus = MutableStateFlow("Code de récupération : non généré.")

    val state: StateFlow<UiState> = combine(
        container.profiles.observeMe(),
        combine(editing, editHandle, editDisplayName, editBio, editVisibility) { open, handle, name, bio, visibility ->
            EditState(open, handle, name, bio, visibility)
        },
        combine(saving, error, recoveryCode, recoveryStatus) { saving, error, code, status ->
            ActionState(saving, error, code, status)
        },
        container.moderation.observeBlocked(),
        container.database.outboxDao().observeCount(),
    ) { profile, edit, action, blocked, pending ->
        UiState(
            profile = profile,
            editing = edit.open,
            editHandle = edit.handle,
            editDisplayName = edit.name,
            editBio = edit.bio,
            editVisibility = edit.visibility,
            saving = action.saving,
            error = action.error,
            recoveryCode = action.code,
            recoveryStatus = action.status,
            blocked = blocked,
            pendingOperations = pending,
            e2eeLabel = container.e2ee.status.badgeLabel(),
            userId = container.session.currentUserId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            container.profiles.pullMyProfile()
            val existing = container.sessionStore.recoveryCode()
            if (existing != null) {
                val decrypted = container.crypto.decryptOrNull(existing)
                recoveryStatus.value = if (decrypted != null) {
                    "Code de récupération enregistré sur cet appareil (affichage unique)."
                } else {
                    "Code de récupération présent mais illisible (clé locale perdue)."
                }
            }
        }
    }

    fun startEditing() {
        val profile = state.value.profile ?: return
        editHandle.value = profile.handle
        editDisplayName.value = profile.displayName
        editBio.value = profile.bio
        editVisibility.value = profile.visibility
        editing.value = true
    }

    fun cancelEditing() {
        editing.value = false
        error.value = null
    }

    fun onHandleChange(value: String) {
        editHandle.value = tomate.data.repo.ProfileRepository.normaliseHandle(value).take(20)
    }

    fun onDisplayNameChange(value: String) {
        editDisplayName.value = value.take(40)
    }

    fun onBioChange(value: String) {
        editBio.value = value.take(280)
    }

    fun onVisibilityChange(value: Visibility) {
        editVisibility.value = value
    }

    fun save() {
        viewModelScope.launch {
            saving.value = true
            val result = container.profiles.saveProfile(
                handle = editHandle.value,
                displayName = editDisplayName.value,
                bio = editBio.value,
                visibility = editVisibility.value,
            )
            saving.value = false
            when (result) {
                is Outcome.Success -> editing.value = false
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun generateRecoveryCode() {
        viewModelScope.launch {
            val code = RecoverySecret.generate()
            val encrypted = container.crypto.encrypt(RecoverySecret.normalise(code))
            container.sessionStore.putRecoveryCode(encrypted)
            recoveryCode.value = code
            recoveryStatus.value =
                "Code de récupération enregistré. Restauration sur un autre appareil : non disponible dans cette version."
        }
    }

    fun clearRecoveryDisplay() {
        recoveryCode.value = null
    }

    fun unblock(userId: String) {
        viewModelScope.launch {
            when (val result = container.moderation.unblock(userId)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            container.session.signOut()
            container.database.profileDao().deleteMe()
            container.sessionStore.putHandle(null)
            onDone()
        }
    }

    fun dismissError() {
        error.value = null
    }
}
