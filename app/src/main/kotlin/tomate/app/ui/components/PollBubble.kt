package tomate.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import tomate.core.designsystem.theme.TomateSpacing
import tomate.data.extras.Poll
import tomate.data.extras.PollTally

/**
 * Sondage dans une conversation.
 *
 * Le dépouillement vient du serveur (les votes sont des messages), donc deux appareils qui ont
 * reçu les mêmes lignes affichent le même résultat. Rien n'est simulé : sans vote, total = 0 et
 * aucune barre n'est remplie. Le vote de l'utilisateur est marqué, ce qui permet de changer
 * d'avis — son vote le plus récent l'emporte.
 */
@Composable
fun PollBubble(
    poll: Poll,
    tally: PollTally,
    isMine: Boolean,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (isMine) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(TomateSpacing.m)) {
                Text(
                    text = "Sondage",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(
                    text = poll.question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(TomateSpacing.s))
                poll.options.forEachIndexed { index, option ->
                    val count = tally.countFor(index)
                    val percent = tally.percentFor(index)
                    val chosen = tally.mine == index
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVote(index) }
                            .padding(vertical = TomateSpacing.xs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (chosen) "◉" else "○",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(TomateSpacing.xs))
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "$count · $percent %",
                                style = MaterialTheme.typography.labelSmall,
                                color = content.copy(alpha = 0.75f),
                            )
                        }
                        Spacer(Modifier.height(TomateSpacing.xs))
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (chosen) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.35f),
                            trackColor = content.copy(alpha = 0.12f),
                        )
                    }
                }
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(
                    text = when {
                        tally.total == 0 -> "Aucun vote pour l'instant"
                        tally.total == 1 -> "1 votant"
                        else -> "${tally.total} votants"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                )
            }
        }
    }
}
