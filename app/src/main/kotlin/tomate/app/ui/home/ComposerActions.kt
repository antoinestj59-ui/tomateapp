package tomate.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.theme.TomateSpacing

/**
 * Ligne d'actions du composeur (compteur, envoi, Photo / Vidéo / Événement).
 *
 * Les trois boutons vivent dans un [FlowRow] : chacun garde son libellé entier et passe à la
 * ligne en bloc. Dans une `Row` avec un `Spacer(weight)`, « Événement » n'avait plus la place et
 * se cassait en « Év / én ».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerActions(
    length: Int,
    canPublish: Boolean,
    posting: Boolean,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onEvent: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetricPill(text = "$length/2000")
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPublish, enabled = canPublish && !posting) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publier")
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TomateSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(TomateSpacing.xs),
    ) {
        TomateTonalButton(text = "Photo", onClick = onPhoto, enabled = !posting)
        TomateTonalButton(text = "Vidéo", onClick = onVideo, enabled = !posting)
        TomateTonalButton(text = "Événement", onClick = onEvent, enabled = !posting)
    }
}
