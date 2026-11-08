package tomate.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.PostCard
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateNetworkBadge
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.NetworkStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    network: NetworkStatus,
    pendingOperations: Int,
    onSyncNow: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = tomateViewModelFactory { HomeViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = "TOMATE", style = MaterialTheme.typography.headlineMedium) },
            actions = {
                TomateNetworkBadge(status = network)
                IconButton(onClick = onSyncNow) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Synchroniser maintenant")
                }
            },
        )

        if (state.error != null) {
            TomateInlineNotice(
                text = state.error!!.userMessage,
                tone = NoticeTone.Error,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        if (state.network == NetworkStatus.Offline) {
            TomateInlineNotice(
                text = "Hors ligne : le fil affiché est celui déjà téléchargé. Vos publications partiront automatiquement au retour du réseau.",
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }
        if (pendingOperations > 0) {
            TomateInlineNotice(
                text = "$pendingOperations opération(s) en attente de synchronisation.",
                tone = NoticeTone.Info,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
        }

        TomateCard(modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s)) {
            Column(modifier = Modifier.padding(TomateSpacing.m)) {
                TomateTextField(
                    value = state.composerText,
                    onValueChange = viewModel::onComposerChange,
                    label = "Partager quelque chose…",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                )
                Spacer(Modifier.height(TomateSpacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricPill(text = "${state.composerText.length}/2000")
                    Spacer(Modifier.padding(horizontal = TomateSpacing.xs))
                    IconButton(
                        onClick = viewModel::publish,
                        enabled = state.composerText.isNotBlank() && !state.posting,
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Publier")
                    }
                }
            }
        }

        when {
            state.loading -> TomateLoadingState()
            state.posts.isEmpty() -> TomateEmptyState(
                title = "Votre fil est vide",
                message = if (state.network == NetworkStatus.Offline) {
                    "Aucune publication téléchargée n'est encore disponible hors ligne."
                } else {
                    "Publiez un premier message ou abonnez-vous à d'autres comptes."
                },
                actionLabel = "Actualiser",
                onAction = viewModel::refresh,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = TomateSpacing.l,
                    end = TomateSpacing.l,
                    top = TomateSpacing.s,
                    bottom = TomateSpacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
            ) {
                items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onOpen = { onOpenPost(post.id) },
                        onReact = { viewModel.react(post) },
                    )
                }
            }
        }
    }
}
