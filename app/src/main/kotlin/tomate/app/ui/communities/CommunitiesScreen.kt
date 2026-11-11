package tomate.app.ui.communities

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateGradientBanner
import tomate.core.designsystem.components.TomateIconTile
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateLogo
import tomate.core.designsystem.components.TomatePillButton
import tomate.core.designsystem.components.TomateSectionHeader
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.compactCount
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.Community
import tomate.core.model.Visibility
import tomate.core.model.label

/**
 * Écran Communautés : bandeau de marque, « Vos communautés », puis « Découvrir ».
 * Chaque bouton « Rejoindre » appelle réellement `community_members` côté serveur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitiesScreen(
    onOpenCommunity: (String) -> Unit,
    openCreateRequest: Boolean = false,
    onCreateRequestConsumed: () -> Unit = {},
    viewModel: CommunitiesViewModel = viewModel(factory = tomateViewModelFactory { CommunitiesViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(openCreateRequest) {
        if (openCreateRequest) {
            viewModel.openDialog()
            onCreateRequestConsumed()
        }
    }

    val mine = state.communities.filter { it.myMembership != null }
    val discover = state.communities.filter { it.myMembership == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Communautés", fontWeight = FontWeight.Bold) },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = TomateSpacing.s)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = viewModel::openDialog),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Créer une communauté")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openDialog,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Créer") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                TomateInlineNotice(
                    text = state.error!!.userMessage,
                    tone = NoticeTone.Error,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
                )
            }
            when {
                state.loading && state.communities.isEmpty() -> TomateLoadingState()
                state.communities.isEmpty() -> TomateEmptyState(
                    icon = Icons.Filled.Groups,
                    title = "Aucune communauté",
                    message = "Créez une communauté publique ou privée : les publications communautaires " +
                        "respectent les règles d'accès côté serveur.",
                    actionLabel = "Créer une communauté",
                    onAction = viewModel::openDialog,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = TomateSpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(TomateSpacing.xs),
                ) {
                    item {
                        TomateGradientBanner(
                            modifier = Modifier.padding(
                                horizontal = TomateSpacing.l,
                                vertical = TomateSpacing.s,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(TomateSpacing.l)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        TomateLogo(size = 32.dp)
                                    }
                                    Spacer(Modifier.width(TomateSpacing.m))
                                    Column {
                                        Text(
                                            text = "TOMATE",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Text(
                                            text = "Une communauté, mille possibilités",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(TomateSpacing.m))
                                TomatePillButton(
                                    text = "Créer la mienne",
                                    onClick = viewModel::openDialog,
                                    filled = false,
                                )
                            }
                        }
                    }

                    if (mine.isNotEmpty()) {
                        item {
                            TomateSectionHeader(title = "Vos communautés")
                        }
                        items(mine, key = { "mine-${it.id}" }) { community ->
                            CommunityRow(
                                community = community,
                                onClick = { onOpenCommunity(community.id) },
                            )
                        }
                    }

                    if (discover.isNotEmpty()) {
                        item {
                            TomateSectionHeader(title = "Découvrir")
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = TomateSpacing.l),
                                horizontalArrangement = Arrangement.spacedBy(TomateSpacing.m),
                            ) {
                                items(discover, key = { "discover-${it.id}" }) { community ->
                                    DiscoverCard(
                                        community = community,
                                        onClick = { onOpenCommunity(community.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.dialogOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeDialog,
            title = { Text("Nouvelle communauté") },
            text = {
                Column {
                    TomateTextField(
                        value = state.name,
                        onValueChange = viewModel::onNameChange,
                        label = "Nom",
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.slug,
                        onValueChange = viewModel::onSlugChange,
                        label = "Identifiant",
                        supportingText = "Utilisé dans les liens et mentions.",
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChange,
                        label = "Description",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    Visibility.entries.filter { it != Visibility.Followers }.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.visibility == option,
                                onClick = { viewModel.onVisibilityChange(option) },
                            )
                            Text(if (option == Visibility.Public) "Publique" else "Privée (adhésion validée)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.create(onOpenCommunity) },
                    enabled = !state.creating && state.name.length >= 3,
                ) { Text(if (state.creating) "Création…" else "Créer") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeDialog) { Text("Annuler") } },
        )
    }
}

@Composable
private fun CommunityRow(
    community: Community,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TomateIconTile(
            icon = Icons.Filled.Groups,
            size = 48,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(TomateSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = community.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${compactCount(community.memberCount)} membres" +
                    community.myMembership?.let { " · ${it.label()}" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiscoverCard(community: Community, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(TomateSpacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TomateIconTile(
                icon = Icons.Filled.Groups,
                size = 40,
                container = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(TomateSpacing.s))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = community.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${compactCount(community.memberCount)} membres",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = community.description.ifBlank { "Aucune description." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TomateSpacing.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricPill(text = community.visibility.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Ouvrir",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
