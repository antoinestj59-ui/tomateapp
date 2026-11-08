package tomate.app.ui.communities

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.PostCard
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.MembershipStatus
import tomate.core.model.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityDetailScreen(
    communityId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: CommunityDetailViewModel = viewModel(
        factory = tomateViewModelFactory { CommunityDetailViewModel(it, communityId) },
        key = "community-$communityId",
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.community?.name ?: "Communauté") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            },
        )

        if (state.loading && state.community == null) {
            TomateLoadingState()
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(TomateSpacing.l),
            verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
        ) {
            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.community?.description?.ifBlank { "Aucune description." }
                                    ?: "Communauté indisponible",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(TomateSpacing.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s)) {
                            MetricPill(text = "${state.community?.memberCount ?: 0} membres")
                            state.community?.visibility?.let { MetricPill(text = it.label) }
                            state.community?.myMembership?.let { MetricPill(text = it.label()) }
                        }
                        Spacer(Modifier.height(TomateSpacing.m))
                        when (state.community?.myMembership) {
                            null -> TomatePrimaryButton(
                                text = if (state.community?.visibility == tomate.core.model.Visibility.Private) {
                                    "Demander à rejoindre"
                                } else {
                                    "Rejoindre"
                                },
                                onClick = viewModel::join,
                                loading = state.busy,
                            )
                            MembershipStatus.Pending -> TomateTonalButton(
                                text = "Demande en attente — annuler",
                                onClick = viewModel::leave,
                                enabled = !state.busy,
                            )
                            else -> TomateTonalButton(
                                text = "Quitter la communauté",
                                onClick = viewModel::leave,
                                enabled = !state.busy,
                            )
                        }
                    }
                }
            }

            if (state.error != null) {
                item { TomateInlineNotice(text = state.error!!.userMessage, tone = NoticeTone.Error) }
            }

            if (state.canPost) {
                item {
                    TomateCard {
                        Column(modifier = Modifier.padding(TomateSpacing.m)) {
                            TomateTextField(
                                value = state.composerText,
                                onValueChange = viewModel::onComposerChange,
                                label = "Publier dans la communauté",
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5,
                            )
                            Spacer(Modifier.height(TomateSpacing.s))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(
                                    onClick = viewModel::publish,
                                    enabled = state.composerText.isNotBlank() && !state.posting,
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publier")
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    TomateInlineNotice(
                        text = "Les publications sont réservées aux membres actifs. Rejoignez la communauté pour participer.",
                        tone = NoticeTone.Info,
                    )
                }
            }

            if (state.posts.isEmpty()) {
                item {
                    TomateEmptyState(
                        title = "Aucune publication",
                        message = "Les publications de cette communauté apparaîtront ici, y compris hors ligne une fois téléchargées.",
                        modifier = Modifier.height(240.dp),
                    )
                }
            } else {
                items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onOpen = { onOpenPost(post.id) },
                        onReact = { },
                    )
                }
            }
        }
    }
}
