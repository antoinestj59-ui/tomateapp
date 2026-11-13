package tomate.app.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateAvatar
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.humanTime
import tomate.core.designsystem.theme.TomateSpacing
import tomate.data.repo.FriendRequest
import tomate.data.repo.FriendsAvailability

/**
 * Demandes d'amis : accepter, refuser, ou demander pourquoi.
 *
 * Chaque bouton écrit réellement sur le serveur ; le refus n'envoie aucune notification
 * publique. La question « pourquoi ? » part comme un vrai message privé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: FriendsViewModel = viewModel(factory = tomateViewModelFactory { FriendsViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Demandes d'amis", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        if (state.availability == FriendsAvailability.Missing) {
            TomateInlineNotice(
                text = "Les demandes d'amis ne sont pas encore activées sur le serveur : " +
                    "appliquez la migration 0012_amis.sql dans le SQL Editor Supabase. " +
                    "Aucune demande ne peut être lue ni envoyée avant.",
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        state.notice?.let { notice ->
            TomateInlineNotice(
                text = notice,
                tone = NoticeTone.Info,
                onDismiss = viewModel::dismissNotice,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
            )
        }
        state.error?.let { error ->
            TomateInlineNotice(
                text = error.userMessage,
                tone = NoticeTone.Error,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
            )
        }

        when {
            state.loading -> TomateLoadingState()
            state.incoming.isEmpty() && state.outgoing.isEmpty() && state.friends.isEmpty() &&
                state.availability == FriendsAvailability.Ready -> TomateEmptyState(
                icon = Icons.Filled.PersonAdd,
                title = "Aucune demande",
                message = "Quand quelqu'un te demandera en ami, sa demande apparaîtra ici et " +
                    "dans tes notifications. Tu pourras accepter, refuser, ou lui demander pourquoi.",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = TomateSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.s),
            ) {
                if (state.incoming.isNotEmpty()) {
                    item { SectionTitle("Reçues · ${state.incoming.size}") }
                    items(state.incoming, key = { "in-${it.id}" }) { request ->
                        IncomingCard(
                            request = request,
                            busy = state.busyId == request.id,
                            onAccept = { viewModel.accept(request) },
                            onRefuse = { viewModel.refuse(request) },
                            onAskWhy = { viewModel.askWhy(request) },
                        )
                    }
                }
                if (state.outgoing.isNotEmpty()) {
                    item { SectionTitle("Envoyées") }
                    items(state.outgoing, key = { "out-${it.id}" }) { request ->
                        OutgoingCard(
                            request = request,
                            busy = state.busyId == request.id,
                            onCancel = { viewModel.cancel(request) },
                        )
                    }
                }
                if (state.friends.isNotEmpty()) {
                    item { SectionTitle("Amis · ${state.friends.size}") }
                    items(state.friends, key = { "friend-${it.id}" }) { request ->
                        FriendCard(request = request)
                    }
                }
            }
        }
    }

    val questionFor = state.questionFor
    if (questionFor != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeQuestion,
            title = { Text("Demander pourquoi ?") },
            text = {
                Column {
                    Text(
                        text = "Ta question part en message privé à ${questionFor.otherLabel}. " +
                            "Rien n'est envoyé automatiquement en ton nom.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.question,
                        onValueChange = viewModel::onQuestionChange,
                        label = "Ta question",
                        singleLine = false,
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.sendQuestion(onOpenConversation) },
                    enabled = state.busyId == null && state.question.isNotBlank(),
                ) { Text("Envoyer") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeQuestion) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
    )
}

@Composable
private fun IncomingCard(
    request: FriendRequest,
    busy: Boolean,
    onAccept: () -> Unit,
    onRefuse: () -> Unit,
    onAskWhy: () -> Unit,
) {
    TomateCard(modifier = Modifier.padding(horizontal = TomateSpacing.l)) {
        Column(modifier = Modifier.padding(TomateSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TomateAvatar(label = request.otherLabel, size = 44)
                Spacer(Modifier.width(TomateSpacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.otherLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (request.otherHandle.isBlank()) {
                            "Demande reçue " + humanTime(request.createdAt)
                        } else {
                            "@${request.otherHandle} · " + humanTime(request.createdAt)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetricPill(text = "En attente")
            }
            if (request.note.isNotBlank()) {
                Spacer(Modifier.height(TomateSpacing.s))
                Text(
                    text = "« ${request.note} »",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(TomateSpacing.m))
            Row(horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s)) {
                TomatePrimaryButton(
                    text = "Accepter",
                    onClick = onAccept,
                    loading = busy,
                    modifier = Modifier.weight(1f),
                )
                TomateTonalButton(
                    text = "Refuser",
                    onClick = onRefuse,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = onAskWhy, enabled = !busy) {
                Text("Demander pourquoi")
            }
        }
    }
}

@Composable
private fun OutgoingCard(request: FriendRequest, busy: Boolean, onCancel: () -> Unit) {
    TomateCard(modifier = Modifier.padding(horizontal = TomateSpacing.l)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TomateSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.otherLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Demande envoyée " + humanTime(request.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel, enabled = !busy) { Text("Annuler") }
        }
    }
}

@Composable
private fun FriendCard(request: FriendRequest) {
    TomateCard(modifier = Modifier.padding(horizontal = TomateSpacing.l)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TomateSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TomateAvatar(label = request.otherLabel, size = 40)
            Spacer(Modifier.width(TomateSpacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.otherLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (request.otherHandle.isNotBlank()) {
                    Text(
                        text = "@${request.otherHandle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
