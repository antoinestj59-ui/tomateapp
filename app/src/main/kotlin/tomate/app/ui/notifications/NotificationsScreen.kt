package tomate.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.humanTime
import tomate.core.designsystem.theme.TomateSpacing
import tomate.data.repo.NotificationRepository
import tomate.data.repo.TomateNotification

/**
 * Notifications réelles, telles que le serveur les a écrites (migration 0007).
 * Tant que la migration n'est pas appliquée, l'écran l'annonce au lieu d'afficher du vide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenFriends: () -> Unit = {},
    viewModel: NotificationsViewModel = viewModel(factory = tomateViewModelFactory { NotificationsViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Notifications") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
            actions = {
                if (state.notifications.any { !it.read }) {
                    IconButton(onClick = viewModel::markAllRead) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "Tout marquer comme lu")
                    }
                }
            },
        )

        if (state.missing) {
            TomateInlineNotice(
                text = "Notifications pas encore activées : appliquez la migration 0007_compte_notifications.sql " +
                    "dans le SQL Editor Supabase. Aucune notification ne peut être affichée avant.",
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        state.error?.let { error ->
            TomateInlineNotice(
                text = error.userMessage,
                tone = NoticeTone.Error,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }

        when {
            state.loading -> TomateLoadingState()
            state.notifications.isEmpty() && !state.missing -> TomateEmptyState(
                icon = Icons.Filled.NotificationsNone,
                title = "Aucune notification",
                message = "Les réactions, commentaires, messages et demandes d'adhésion apparaîtront ici " +
                    "dès que le serveur en créera.",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = TomateSpacing.s),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.xxs),
            ) {
                items(state.notifications, key = { it.id }) { notification ->
                    NotificationRow(
                        notification = notification,
                        onClick = {
                            viewModel.markRead(notification)
                            val conversation = notification.conversationId
                            when {
                                conversation != null && notification.kind == "message" ->
                                    onOpenConversation(conversation)
                                notification.kind == "reaction" || notification.kind == "comment" ->
                                    notification.entityId?.let(onOpenPost)
                                notification.kind == "friend_request" ||
                                    notification.kind == "friend_accept" -> onOpenFriends()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: TomateNotification, onClick: () -> Unit) {
    val icon = when (notification.kind) {
        "message" -> Icons.Filled.ModeComment
        "reaction" -> Icons.Filled.Favorite
        "comment" -> Icons.Filled.ModeComment
        "join" -> Icons.Filled.Groups
        "story" -> Icons.Filled.PhotoCamera
        "friend_request", "friend_accept" -> Icons.Filled.PersonAdd
        else -> Icons.Filled.NotificationsNone
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (notification.read) MaterialTheme.colorScheme.surfaceContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (notification.read) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(TomateSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = NotificationRepository.kindLabel(notification.kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = notification.body.ifBlank { "Sans texte" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = humanTime(notification.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!notification.read) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
