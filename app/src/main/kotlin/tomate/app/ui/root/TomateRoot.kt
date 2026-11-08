package tomate.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.navigation.TomateApp
import tomate.app.ui.onboarding.OnboardingScreen
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.TomateErrorState
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.theme.ThemeMode
import tomate.core.designsystem.theme.TomateTheme

/**
 * Application shell: owns the theme, waits for the anonymous session, then shows onboarding on
 * first launch or the main navigation.
 */
@Composable
fun TomateRoot() {
    val viewModel: RootViewModel = viewModel(factory = tomateViewModelFactory { RootViewModel(it) })
    val state by viewModel.state.collectAsStateWithLifecycle()

    val themeMode = runCatching { ThemeMode.valueOf(state.settings.themeMode) }
        .getOrDefault(ThemeMode.System)

    TomateTheme(
        themeMode = themeMode,
        dynamicColor = state.settings.dynamicColor,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                state.booting -> TomateLoadingState(label = "Démarrage de TOMATE…")
                state.error != null && state.profile == null && state.needsOnboarding.not() ->
                    TomateErrorState(error = state.error!!, onRetry = { viewModel.bootstrap() })
                state.needsOnboarding -> OnboardingScreen(
                    onCompleted = { viewModel.onOnboardingCompleted() },
                )
                else -> Box(Modifier.fillMaxSize()) {
                    TomateApp(
                        network = state.network,
                        pendingOperations = state.pendingOperations,
                        onSyncNow = { viewModel.syncNow() },
                        themeMode = themeMode,
                        onThemeModeChange = { viewModel.setThemeMode(it) },
                        dynamicColor = state.settings.dynamicColor,
                        onDynamicColorChange = { viewModel.setDynamicColor(it) },
                        relayEnabled = state.settings.relayEnabled,
                        onRelayEnabledChange = { viewModel.setRelayEnabled(it) },
                        batterySaver = state.settings.batterySaver,
                        onBatterySaverChange = { viewModel.setBatterySaver(it) },
                    )
                }
            }
        }
    }
}
