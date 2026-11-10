package tomate.app.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Libellé d'onglet de la barre du bas.
 *
 * Cinq onglets doivent tenir sur 360 dp. Avec le style par défaut, « Communautés » se coupait en
 * « Communau / tés ». Le libellé reste donc sur une seule ligne, en 10 sp, quitte à être tronqué
 * proprement sur un écran plus étroit.
 */
@Composable
fun NavigationTabLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            letterSpacing = (-0.2).sp,
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
