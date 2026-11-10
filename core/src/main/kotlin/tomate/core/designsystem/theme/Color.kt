package tomate.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Identité TOMATE — Material 3 Expressive.
 *
 * Rouge tomate employé avec modération : primaire pour les actions, conteneurs tonals pour
 * les surfaces. Fonds clairs légèrement chauds, sombres profonds et soignés.
 */

// Marque
val TomatoRed = Color(0xFFE03A24)
val TomatoRedDeep = Color(0xFF8C1D10)
val TomatoFlesh = Color(0xFFFF8A73)
val TomatoLeaf = Color(0xFF3E6B34)
val TomatoAmber = Color(0xFFE8A100)

// Surfaces chaudes
val WarmInk = Color(0xFF221614)
val WarmSurface = Color(0xFFFFF7F3)
val WarmSurfaceVariant = Color(0xFFF6E2D9)
val WarmOutline = Color(0xFF85736C)
val WarmOutlineVariant = Color(0xFFE4CFC5)

private val LightScheme = lightColorScheme(
    primary = TomatoRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD1),
    onPrimaryContainer = Color(0xFF410B02),
    inversePrimary = TomatoFlesh,
    secondary = Color(0xFF6B4B3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2A160E),
    tertiary = TomatoLeaf,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDF0B2),
    onTertiaryContainer = Color(0xFF02210A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = WarmSurface,
    onBackground = WarmInk,
    surface = WarmSurface,
    onSurface = WarmInk,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = Color(0xFF53433D),
    surfaceTint = TomatoRed,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFBE9E1),
    surfaceContainerHigh = Color(0xFFF5DED5),
    surfaceContainerHighest = Color(0xFFEDD2C7),
    inverseSurface = Color(0xFF382925),
    inverseOnSurface = Color(0xFFFFEDE7),
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB4A3),
    onPrimary = Color(0xFF680E00),
    primaryContainer = TomatoRedDeep,
    onPrimaryContainer = Color(0xFFFFDAD1),
    inversePrimary = TomatoRed,
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A21),
    secondaryContainer = Color(0xFF5D4037),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFF9ED69A),
    onTertiary = Color(0xFF04310A),
    tertiaryContainer = Color(0xFF1C4E22),
    onTertiaryContainer = Color(0xFFBDF0B2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF171110),
    onBackground = Color(0xFFF3DFD8),
    surface = Color(0xFF171110),
    onSurface = Color(0xFFF3DFD8),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceTint = Color(0xFFFFB4A3),
    surfaceContainerLowest = Color(0xFF100C0B),
    surfaceContainerLow = Color(0xFF1F1A18),
    surfaceContainer = Color(0xFF241E1C),
    surfaceContainerHigh = Color(0xFF2E2724),
    surfaceContainerHighest = Color(0xFF3A3230),
    inverseSurface = Color(0xFFF3DFD8),
    inverseOnSurface = Color(0xFF382925),
    outline = Color(0xFFA08C85),
    outlineVariant = Color(0xFF53433D),
    scrim = Color.Black,
)

fun tomateLightColorScheme() = LightScheme

fun tomateDarkColorScheme() = DarkScheme

/** Couleurs sémantiques que Material 3 ne modélise pas (réseau, modération). */
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
    success = Color(0xFF2E6B34),
    onSuccess = Color.White,
    successContainer = Color(0xFFBDF0B2),
    onSuccessContainer = Color(0xFF02210A),
    warning = Color(0xFF7A5900),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF261900),
    info = Color(0xFF1F5C8B),
    onInfo = Color.White,
    infoContainer = Color(0xFFD3E6FF),
    onInfoContainer = Color(0xFF001D33),
)

val DarkStatusColors = TomateStatusColors(
    success = Color(0xFF9ED69A),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF1C4E22),
    onSuccessContainer = Color(0xFFBDF0B2),
    warning = Color(0xFFF0C14B),
    onWarning = Color(0xFF3F2E00),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDEA6),
    info = Color(0xFF9CCAFF),
    onInfo = Color(0xFF003354),
    infoContainer = Color(0xFF00496F),
    onInfoContainer = Color(0xFFD3E6FF),
)
