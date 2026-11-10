package tomate.app.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.designsystem.theme.ThemeMode
import tomate.core.model.AppError
import tomate.core.model.NetworkStatus
import tomate.core.model.Outcome
import tomate.core.model.Profile
import tomate.data.container.TomateContainer
import tomate.data.e2ee.E2eeStatus
import tomate.data.settings.TomateSettings

/**
 * Application shell state: bootstrap of the anonymous session, onboarding gate, theme,
 * connectivity and pending-work counter.
 */
class RootViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val booting: Boolean = true,
        val needsOnboarding: Boolean = false,
        val profile: Profile? = null,
        val network: NetworkStatus = NetworkStatus.Checking,
        val pendingOperations: Int = 0,
        val error: AppError? = null,
        val settings: TomateSettings = TomateSettings(),
        val e2eeStatus: E2eeStatus = E2eeStatus.NotConfigured,
        /** Non-blocking message: account linked, pseudo taken server-side, etc. */
        val notice: String? = null,
    )

    private val booting = MutableStateFlow(true)
    private val onboarding = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val profileFlow = container.profiles.observeMe()
    private val pendingFlow = container.database.outboxDao().observeCount()
    private val settingsFlow = container.settings.settings
    private val networkFlow = container.networkMonitor.status()

    private data class Data(val profile: Profile?, val pending: Int, val settings: TomateSettings, val network: NetworkStatus)

    private val dataFlow = combine(
        combine(profileFlow, pendingFlow) { profile, pending -> profile to pending },
        combine(settingsFlow, networkFlow) { settings, network -> settings to network },
    ) { (profile, pending), (settings, network) ->
        Data(profile, pending, settings, network)
    }

    val state: StateFlow<UiState> = combine(
        booting,
        onboarding,
        error,
        dataFlow,
        notice,
    ) { isBooting, needsOnboarding, currentError, data, currentNotice ->
        UiState(
            booting = isBooting,
            // No profile means no account: this is what brings the user back to onboarding
            // after a sign-out, instead of leaving them on an empty profile screen.
            needsOnboarding = needsOnboarding || data.profile == null,
            error = currentError,
            profile = data.profile,
            pendingOperations = data.pending,
            settings = data.settings,
            network = data.network,
            e2eeStatus = container.e2ee.status,
            notice = currentNotice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        bootstrap()
    }

    fun bootstrap() {
        viewModelScope.launch {
            booting.value = true
            error.value = null
            when (val result = container.bootstrap()) {
                is Outcome.Success -> {
                    // One-shot read through the DAO keeps bootstrap deterministic.
                    val profile = container.database.profileDao().me()
                    onboarding.value = profile?.handle.isNullOrBlank()
                }
                is Outcome.Failure -> {
                    val localProfile = container.database.profileDao().me()
                    onboarding.value = localProfile == null
                    error.value = result.error
                }
            }
            // A local account created offline is migrated as soon as the network allows it.
            linkIfNeeded(notify = false)
            booting.value = false
        }
    }

    fun onOnboardingCompleted() {
        onboarding.value = false
        viewModelScope.launch {
            runCatching { container.profiles.pullMyProfile() }
            linkIfNeeded(notify = true)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            if (container.session.isLocalAccount()) {
                linkIfNeeded(notify = true)
            } else {
                runCatching { container.sync.drain() }
            }
        }
    }

    private suspend fun linkIfNeeded(notify: Boolean) {
        if (!container.session.isLocalAccount()) return
        when (val result = container.account.linkIfNeeded()) {
            is Outcome.Success -> when {
                result.value.linked -> {
                    notice.value = "Compte relié au réseau. Vos données locales et vos opérations en attente " +
                        "vont se synchroniser automatiquement."
                    runCatching { container.sync.drain() }
                }
                else -> Unit
            }
            is Outcome.Failure -> if (notify) {
                if (!result.error.isOffline) notice.value = result.error.userMessage
            }
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(mode.name) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { container.settings.setDynamicColor(enabled) }
    }

    fun setRelayEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setRelayEnabled(enabled) }
    }

    /** Le relais TURN reste sur l'appareil : il n'est jamais publié ni envoyé. */
    fun saveTurn(url: String, user: String, password: String) {
        viewModelScope.launch { container.settings.setTurn(url, user, password) }
    }

    fun clearTurn() {
        viewModelScope.launch { container.settings.clearTurn() }
    }

    fun setBatterySaver(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBatterySaver(enabled) }
    }

    fun dismissError() {
        error.value = null
    }

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(state.value.settings.themeMode)
    }.getOrDefault(ThemeMode.System)
}
