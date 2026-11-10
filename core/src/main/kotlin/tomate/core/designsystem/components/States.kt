package tomate.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.designsystem.theme.TomateTheme
import tomate.core.model.AppError

@Composable
fun TomateLoadingState(
    label: String = "Chargement…",
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(TomateSpacing.m))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun TomateEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TomateSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(icon = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(TomateSpacing.l))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(TomateSpacing.l))
            TomateTonalButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun TomateErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val status = TomateTheme.status
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TomateSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(
            icon = if (error.isOffline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (error.isOffline) status.warning else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(TomateSpacing.l))
        Text(
            text = if (error.isOffline) "Hors ligne" else "Un problème est survenu",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = error.userMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (onRetry != null && error.isRetryable) {
            Spacer(Modifier.height(TomateSpacing.l))
            TomateTonalButton(text = "Réessayer", onClick = onRetry)
        }
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** Compact inline banner used for "stale data / waiting for sync" hints. */
@Composable
fun TomateInlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.CloudOff,
    tone: NoticeTone = NoticeTone.Info,
    /**
     * Ajoute une croix de fermeture. Un bandeau d'erreur sans issue restait affiché pour
     * toujours, même une fois la cause disparue : il doit pouvoir être écarté.
     */
    onDismiss: (() -> Unit)? = null,
) {
    val status = TomateTheme.status
    val container = when (tone) {
        NoticeTone.Info -> status.infoContainer
        NoticeTone.Warning -> status.warningContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.Info -> status.onInfoContainer
        NoticeTone.Warning -> status.onWarningContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TomateSpacing.m, vertical = TomateSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(TomateSpacing.s))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Fermer",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

enum class NoticeTone { Info, Warning, Error }
