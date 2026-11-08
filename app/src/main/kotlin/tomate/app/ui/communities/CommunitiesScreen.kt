package tomate.app.ui.communities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateLoadingState
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.Visibility
import tomate.core.model.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitiesScreen(
    onOpenCommunity: (String) -> Unit,
    viewModel: CommunitiesViewModel = viewModel(factory = tomateViewModelFactory { CommunitiesViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Communautés") }) },
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
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                )
            }
            when {
                state.loading -> TomateLoadingState()
                state.communities.isEmpty() -> TomateEmptyState(
                    icon = Icons.Filled.Groups,
                    title = "Aucune communauté",
                    message = "Créez une communauté publique ou privée : les publications communautaires respectent les règles d'accès côté serveur.",
                    actionLabel = "Créer une communauté",
                    onAction = viewModel::openDialog,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(TomateSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
                ) {
                    items(state.communities, key = { it.id }) { community ->
                        TomateCard(onClick = { onOpenCommunity(community.id) }) {
                            Column(modifier = Modifier.padding(TomateSpacing.l)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = community.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    MetricPill(text = community.visibility.label)
                                }
                                Spacer(Modifier.height(TomateSpacing.xs))
                                Text(
                                    text = community.description.ifBlank { "Aucune description." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(TomateSpacing.xs))
                                Text(
                                    text = "${community.memberCount} membre(s)" +
                                        (community.myMembership?.let { " · ${it.name}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
