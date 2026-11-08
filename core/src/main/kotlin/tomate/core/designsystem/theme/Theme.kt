package tomate.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.os.Build

enum class ThemeMode { System, Light, Dark }

private val LocalTomateStatusColors = staticCompositionLocalOf { LightStatusColors }
private val LocalReduceMotion = staticCompositionLocalOf { false }

object TomateTheme {
    val status: TomateStatusColors
        @Composable @ReadOnlyComposable get() = LocalTomateStatusColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * Application theme.
 *
 * Dynamic colour is opt-in and only used when the caller explicitly asks for it, because the
 * tomato identity must stay recognisable. Material You support is wired but disabled by default.
 */
@Composable
fun TomateTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val baseScheme = if (dark) tomateDarkColorScheme() else tomateLightColorScheme()
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = if (dark) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
        dynamic
    } else {
        baseScheme
    }

    CompositionLocalProvider(
        LocalTomateStatusColors provides if (dark) DarkStatusColors else LightStatusColors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TomateTypography,
            shapes = TomateShapes,
            content = content,
        )
    }
}
