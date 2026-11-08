package tomate.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Centralised colour tokens for TOMATE. Nothing in the UI layer should hardcode a colour:
 * every screen reads the MaterialTheme colour scheme or the [TomateStatusColors].
 */

// Brand
val TomatoRed = Color(0xFFC0392B)
val TomatoRedDeep = Color(0xFF7C2418)
val TomatoFlesh = Color(0xFFFF8A73)
val TomatoLeaf = Color(0xFF4C6B3C)

// Neutrals (warm, tomato-tinted)
val WarmInk = Color(0xFF231917)
val WarmSurface = Color(0xFFFFF8F6)
val WarmSurfaceVariant = Color(0xFFF5DED8)
val WarmOutline = Color(0xFF85736E)
val WarmOutlineVariant = Color(0xFFD8C2BC)

private val LightScheme = lightColorScheme(
    primary = TomatoRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD3),
    onPrimaryContainer = Color(0xFF3F0A03),
    inversePrimary = TomatoFlesh,
    secondary = Color(0xFF77574E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBD1),
    onSecondaryContainer = Color(0xFF2C160F),
    tertiary = Color(0xFF6B5D2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF231B00),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = WarmSurface,
    onBackground = WarmInk,
    surface = WarmSurface,
    onSurface = WarmInk,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = Color(0xFF53433F),
    surfaceTint = TomatoRed,
    inverseSurface = Color(0xFF3A2E2C),
    inverseOnSurface = Color(0xFFFFEDE8),
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB4A6),
    onPrimary = Color(0xFF641E12),
    primaryContainer = Color(0xFF8C2B1C),
    onPrimaryContainer = Color(0xFFFFDAD3),
    inversePrimary = TomatoRed,
    secondary = Color(0xFFE7BDB2),
    onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4038),
    onSecondaryContainer = Color(0xFFFFDBD1),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3A2F05),
    tertiaryContainer = Color(0xFF524619),
    onTertiaryContainer = Color(0xFFF5E1A7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    surfaceTint = Color(0xFFFFB4A6),
    inverseSurface = Color(0xFFF1DFDA),
    inverseOnSurface = Color(0xFF3A2E2C),
    outline = Color(0xFFA08C86),
    outlineVariant = Color(0xFF53433F),
    scrim = Color.Black,
)

fun tomateLightColorScheme() = LightScheme

fun tomateDarkColorScheme() = DarkScheme

/** Semantic colours that Material 3 does not model directly (network / moderation states). */
data class TomateStatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

val LightStatusColors = TomateStatusColors(
    success = Color(0xFF2E6B3E),
    onSuccess = Color.White,
    successContainer = Color(0xFFB8F0BE),
    onSuccessContainer = Color(0xFF00210B),
    warning = Color(0xFF7A5900),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF261900),
    info = Color(0xFF1F5C8B),
    onInfo = Color.White,
    infoContainer = Color(0xFFCFE5FF),
    onInfoContainer = Color(0xFF001D33),
)

val DarkStatusColors = TomateStatusColors(
    success = Color(0xFF9CD49F),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF14522A),
    onSuccessContainer = Color(0xFFB8F0BE),
    warning = Color(0xFFF0C14B),
    onWarning = Color(0xFF3F2E00),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDEA6),
    info = Color(0xFF9CCAFF),
    onInfo = Color(0xFF003354),
    infoContainer = Color(0xFF00496F),
    onInfoContainer = Color(0xFFCFE5FF),
)
