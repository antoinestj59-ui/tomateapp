package tomate.app.ui.communities

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.components.PostCard
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateAvatar
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateLogo
import tomate.core.designsystem.components.TomatePillButton
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.compactCount
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.MembershipStatus
import tomate.core.model.label

/**
 * Détail d'une communauté : bannière, présentation, onglets Publications / Membres / À propos.
 * L'onglet Membres lit les adhésions réelles ; l'onglet À propos reprend les données du serveur.
 */
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
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val community = state.community
    // Un onglet d'administration n'apparaît que pour un rôle réel (propriétaire ou modérateur).
    val tabs = if (state.isModerator) {
        listOf("Publications", "Membres", "Admin", "À propos")
    } else {
        listOf("Publications", "Membres", "À propos")
    }

    if (state.loading && community == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TomateLoadingState()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TomateSpacing.xxl),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(TomateSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Communauté TOMATE : ${community?.name.orEmpty()}")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(share, "Partager la communauté"))
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Partager",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = TomateSpacing.l)) {
                Box(
                    modifier = Modifier
                        .offset(y = (-34).dp)
                        .size(74.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        TomateLogo(size = 40.dp)
                    }
                }
                Text(
                    text = community?.name ?: "Communauté",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(y = (-22).dp),
                )
                Text(
                    text = "${compactCount(community?.memberCount ?: 0)} membres · " +
                        (community?.visibility?.label ?: "Visibilité inconnue"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = (-18).dp),
                )
                if (!community?.description.isNullOrBlank()) {
                    Text(
                        text = community!!.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.offset(y = (-10).dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .offset(y = (-2).dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s),
                ) {
                    when (community?.myMembership) {
                        null -> TomatePrimaryButton(
                            text = if (community?.visibility == tomate.core.model.Visibility.Private) {
                                "Demander à rejoindre"
                            } else {
                                "Rejoindre"
                            },
                            onClick = viewModel::join,
                            loading = state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        MembershipStatus.Pending -> TomateTonalButton(
                            text = "Demande en attente — annuler",
                            onClick = viewModel::leave,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        else -> TomateTonalButton(
                            text = "Quitter la communauté",
                            onClick = viewModel::leave,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    community?.myMembership?.let { MetricPill(text = it.label()) }
                }
            }
        }

        item {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(top = TomateSpacing.s),
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        }

        if (state.error != null) {
            item {
                TomateInlineNotice(
                    text = state.error!!.userMessage,
                    tone = NoticeTone.Error,
                    onDismiss = viewModel::dismissError,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                )
            }
        }

        when (tab) {
            0 -> {
                if (state.canPost) {
                    item {
                        TomateCard(modifier = Modifier.padding(TomateSpacing.l)) {
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
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
                            text = when (community?.myMembership) {
                                MembershipStatus.Pending ->
                                    "Votre demande d'adhésion est en attente de validation par un responsable."
                                MembershipStatus.Rejected ->
                                    "Votre demande d'adhésion a été refusée."
                                MembershipStatus.Banned ->
                                    "Vous ne pouvez plus participer à cette communauté."
                                else ->
                                    "Les publications sont réservées aux membres actifs. Rejoignez la communauté pour participer."
                            },
                            tone = NoticeTone.Info,
                            modifier = Modifier.padding(horizontal = TomateSpacing.l),
                        )
                    }
                }

                if (state.posts.isEmpty()) {
                    item {
                        TomateEmptyState(
                            title = "Aucune publication",
                            message = "Les publications de cette communauté apparaîtront ici, " +
                                "y compris hors ligne une fois téléchargées.",
                            modifier = Modifier.height(240.dp),
                        )
                    }
                } else {
                    items(state.posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onOpen = { onOpenPost(post.id) },
                            onReact = { viewModel.react(post) },
                            modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
                        )
                    }
                }
            }

            1 -> {
                when {
                    state.membersLoading && state.members.isEmpty() -> item { TomateLoadingState() }
                    state.members.isEmpty() -> item {
                        TomateEmptyState(
                            icon = Icons.Filled.Groups,
                            title = "Aucun membre visible",
                            message = "Les profils masqués par leur confidentialité n'apparaissent pas ici. " +
                                "Tirez pour actualiser après avoir rejoint la communauté.",
                            actionLabel = "Actualiser",
                            onAction = viewModel::reloadMembers,
                        )
                    }
                    else -> items(state.members.filter { it.status == null || it.status == "Active" }, key = { it.id }) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TomateAvatar(
                                label = member.displayName.ifBlank { member.handle },
                                size = 44,
                            )
                            Spacer(Modifier.width(TomateSpacing.m))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = member.displayName.ifBlank { member.handle },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "@${member.handle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            member.role?.let { MetricPill(text = it) }
                        }
                    }
                }
            }

            2 -> if (state.isModerator) {
                item { ModerationSection(state, viewModel) }
            } else {
                item { AboutSection(community) }
            }

            else -> item { AboutSection(community) }
        }
    }
}

/** Onglet « À propos » : uniquement les données du serveur, aucune statistique inventée. */
@Composable
private fun AboutSection(community: tomate.core.model.Community?) {
    Column(modifier = Modifier.padding(TomateSpacing.l)) {
        Text("À propos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = community?.description?.ifBlank { "Aucune description fournie." }
                ?: "Communauté indisponible.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(TomateSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s)) {
            MetricPill(text = "${compactCount(community?.memberCount ?: 0)} membres")
            community?.visibility?.let { MetricPill(text = it.label) }
            community?.myMembership?.let { MetricPill(text = it.label()) }
        }
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = "Identifiant : ${community?.slug ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Onglet « Admin » : accepter ou refuser les demandes d'adhésion, gérer les rôles.
 *
 * Chaque bouton écrit sur `community_members` ; la politique RLS refuse l'action à un simple
 * membre. En cas de refus serveur, le message est affiché tel quel : rien n'est simulé.
 */
@Composable
private fun ModerationSection(
    state: CommunityDetailViewModel.UiState,
    viewModel: CommunityDetailViewModel,
) {
    val pending = state.members.filter { it.status == "Pending" }
    val active = state.members.filter { it.status == null || it.status == "Active" }

    Column(modifier = Modifier.padding(TomateSpacing.l)) {
        Text(
            text = "Demandes d'adhésion",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(TomateSpacing.s))
        if (pending.isEmpty()) {
            Text(
                text = "Aucune demande en attente.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            pending.forEach { member ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = TomateSpacing.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TomateAvatar(label = member.displayName.ifBlank { member.handle }, size = 40)
                        Spacer(Modifier.width(TomateSpacing.m))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.displayName.ifBlank { member.handle },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "@${member.handle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MetricPill(text = "En attente")
                    }
                    Spacer(Modifier.height(TomateSpacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s)) {
                        TomatePrimaryButton(
                            text = "Accepter",
                            onClick = { viewModel.decide(member, accept = true) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        TomateTonalButton(
                            text = "Refuser",
                            onClick = { viewModel.decide(member, accept = false) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(TomateSpacing.l))
        Text(
            text = "Membres et rôles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(TomateSpacing.xs))
        if (active.isEmpty()) {
            Text(
                text = "Aucun membre actif à gérer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            active.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = TomateSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.displayName.ifBlank { member.handle },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "@${member.handle}" + (member.role?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (member.role != "Owner") {
                        if (state.isOwner && member.role != "Moderator") {
                            TextButton(
                                onClick = { viewModel.promote(member) },
                                enabled = !state.busy,
                            ) { Text("Modérateur") }
                        }
                        TextButton(
                            onClick = { viewModel.removeMember(member) },
                            enabled = !state.busy,
                        ) { Text("Retirer") }
                    }
                }
            }
        }
    }
}
