package tomate.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tomate.core.designsystem.theme.TomatoLeaf
import tomate.core.designsystem.theme.TomatoRed

/**
 * Logo TOMATE minimaliste : une tomate dessinée en vectoriel, sans dépendance d'image.
 * Utilisé dans les barres supérieures, les états vides et l'écran À propos.
 */
@Composable
fun TomateLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.72f)) {
            val w = this.size.width
            val h = this.size.height
            // Corps de la tomate.
            drawCircle(
                color = TomatoRed,
                radius = w * 0.38f,
                center = Offset(w * 0.5f, h * 0.56f),
            )
            // Reflet.
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = w * 0.10f,
                center = Offset(w * 0.38f, h * 0.46f),
            )
            // Pédoncule : petite étoile verte tracée en chemin.
            val leaf = Path().apply {
                moveTo(w * 0.5f, h * 0.22f)
                lineTo(w * 0.60f, h * 0.10f)
                lineTo(w * 0.60f, h * 0.20f)
                lineTo(w * 0.72f, h * 0.16f)
                lineTo(w * 0.62f, h * 0.26f)
                lineTo(w * 0.72f, h * 0.32f)
                lineTo(w * 0.58f, h * 0.30f)
                lineTo(w * 0.54f, h * 0.40f)
                lineTo(w * 0.46f, h * 0.40f)
                lineTo(w * 0.42f, h * 0.30f)
                lineTo(w * 0.28f, h * 0.32f)
                lineTo(w * 0.38f, h * 0.26f)
                lineTo(w * 0.28f, h * 0.16f)
                lineTo(w * 0.40f, h * 0.20f)
                lineTo(w * 0.40f, h * 0.10f)
                close()
            }
            drawPath(leaf, TomatoLeaf)
            // Tige.
            drawLine(
                color = TomatoLeaf,
                start = Offset(w * 0.5f, h * 0.22f),
                end = Offset(w * 0.5f, h * 0.08f),
                strokeWidth = w * 0.05f,
            )
        }
    }
}

/** Petites initiales utilisées pour les avatars de repli (aucune image réseau). */
@Composable
fun TomateInitialsAvatar(
    label: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
) {
    val initials = label.trim().take(2).uppercase().ifBlank { "?" }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
