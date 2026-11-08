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
    )

    private val booting = MutableStateFlow(true)
    private val onboarding = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

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
    ) { isBooting, needsOnboarding, currentError, data ->
        UiState(
            booting = isBooting,
            needsOnboarding = needsOnboarding,
            error = currentError,
            profile = data.profile,
            pendingOperations = data.pending,
            settings = data.settings,
            network = data.network,
            e2eeStatus = container.e2ee.status,
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
            booting.value = false
        }
    }

    fun onOnboardingCompleted() {
        onboarding.value = false
        viewModelScope.launch { runCatching { container.profiles.pullMyProfile() } }
    }

    fun syncNow() {
        viewModelScope.launch { runCatching { container.sync.drain() } }
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
