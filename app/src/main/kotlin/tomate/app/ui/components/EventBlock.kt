package tomate.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.theme.TomateSpacing
import tomate.data.extras.EventInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** « samedi 14 novembre à 19:30 » — date lisible, fuseau de l'appareil. */
fun EventInfo.formattedDate(): String {
    val moment = instant ?: return startsAt
    val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH)
    return runCatching { formatter.format(moment.atZone(ZoneId.systemDefault())) }
        .getOrDefault(Instant.EPOCH.let { startsAt })
}

/**
 * Événement affiché dans une publication : titre, date, lieu, détails et, quand l'écran de
 * détail le permet, le nombre de participants réel et le bouton pour rejoindre.
 */
@Composable
fun EventBlock(
    event: EventInfo,
    going: Int? = null,
    mine: Boolean = false,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(TomateSpacing.m)) {
            Text(
                text = "Événement",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(TomateSpacing.xs))
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(TomateSpacing.xs))
            Text(text = event.formattedDate(), style = MaterialTheme.typography.bodyMedium)
            if (event.place.isNotBlank()) {
                Text(
                    text = event.place,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            if (event.details.isNotBlank()) {
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(text = event.details, style = MaterialTheme.typography.bodyMedium)
            }
            if (onToggle != null && going != null) {
                Spacer(Modifier.height(TomateSpacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (going) {
                            0 -> "Aucun participant"
                            1 -> "1 participant"
                            else -> "$going participants"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TomateTonalButton(
                        text = if (mine) "Ne plus participer" else "Je participe",
                        onClick = onToggle,
                    )
                }
            }
        }
    }
}
