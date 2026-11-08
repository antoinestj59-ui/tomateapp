package tomate.app.ui.profile

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateAvatar
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.Visibility
import tomate.core.model.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = tomateViewModelFactory { ProfileViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Profil") },
            actions = {
                IconButton(onClick = viewModel::startEditing) {
                    Icon(Icons.Filled.Edit, contentDescription = "Modifier le profil")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(TomateSpacing.l),
            verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
        ) {
            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TomateAvatar(
                                label = state.profile?.displayName ?: "?",
                                size = 64,
                            )
                            Spacer(Modifier.width(TomateSpacing.s))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.profile?.displayName ?: "Profil non configuré",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Text(
                                    text = state.profile?.let { "@${it.handle}" } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.profile?.visibility?.let { MetricPill(text = it.label) }
                        }
                        if (!state.profile?.bio.isNullOrBlank()) {
                            Spacer(Modifier.height(TomateSpacing.m))
                            Text(text = state.profile!!.bio, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(TomateSpacing.s))
                        Text(
                            text = "Identifiant de compte : ${state.userId ?: "aucune session"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (state.error != null) {
                item { TomateInlineNotice(text = state.error!!.userMessage, tone = NoticeTone.Error) }
            }

            if (state.userId?.startsWith("local-") == true) {
                item {
                    TomateInlineNotice(
                        text = "Compte local : créé hors connexion. Il sera relié automatiquement à Supabase dès " +
                            "qu'une connexion sera disponible, sans perte de vos publications ni de vos messages. " +
                            "Le pseudo sera alors vérifié en ligne.",
                        tone = NoticeTone.Info,
                    )
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Text("Chiffrement des messages", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.xs))
                        TomateInlineNotice(
                            text = "État : ${state.e2eeLabel}. TOMATE n'annonce pas d'E2EE tant qu'un protocole éprouvé n'est pas intégré et vérifié.",
                            tone = NoticeTone.Warning,
                        )
                    }
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Text("Récupération de compte", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = state.recoveryStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(TomateSpacing.s))
                        TomateTonalButton(
                            text = "Générer un code de récupération",
                            onClick = viewModel::generateRecoveryCode,
                        )
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = "Le code est stocké chiffré sur cet appareil. La restauration sur un autre appareil " +
                                "nécessite une fonction serveur qui n'est pas déployée : elle est donc affichée comme non disponible.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.recoveryCode != null) {
                            Spacer(Modifier.height(TomateSpacing.s))
                            TomateInlineNotice(
                                text = "Notez ce code maintenant, il ne sera plus affiché : ${state.recoveryCode}",
                                tone = NoticeTone.Info,
                            )
                            TextButton(onClick = viewModel::clearRecoveryDisplay) {
                                Text("J'ai noté le code")
                            }
                        }
                    }
                }
            }

            if (state.blocked.isNotEmpty()) {
                item {
                    Text("Comptes bloqués", style = MaterialTheme.typography.titleMedium)
                }
                items(state.blocked, key = { it.userId }) { blocked ->
                    TomateCard {
                        Row(
                            modifier = Modifier.padding(TomateSpacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "@${blocked.handle.ifBlank { blocked.userId.take(8) }}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.unblock(blocked.userId) }) {
                                Text("Débloquer")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Opérations en attente : ${state.pendingOperations}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                TomateTonalButton(
                    text = "Se déconnecter",
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(
                    text = "La déconnexion supprime la session et le profil local de cet appareil. " +
                        "Sans code de récupération, le compte anonyme n'est plus accessible.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (state.editing) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEditing,
            title = { Text("Modifier mon profil") },
            text = {
                Column {
                    TomateTextField(
                        value = state.editHandle,
                        onValueChange = viewModel::onHandleChange,
                        label = "Pseudo",
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.editDisplayName,
                        onValueChange = viewModel::onDisplayNameChange,
                        label = "Nom affiché",
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    TomateTextField(
                        value = state.editBio,
                        onValueChange = viewModel::onBioChange,
                        label = "Bio",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    Visibility.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.editVisibility == option,
                                onClick = { viewModel.onVisibilityChange(option) },
                            )
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::save, enabled = !state.saving) {
                    Text(if (state.saving) "Enregistrement…" else "Enregistrer")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelEditing) { Text("Annuler") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Se déconnecter ?") },
            text = {
                Text(
                    "La session, le profil et toutes les données locales de cet appareil seront " +
                        "supprimés, y compris les opérations en attente. Cette action est irréversible " +
                        "sans code de récupération.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut { }
                }) { Text("Se déconnecter") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Annuler") } },
        )
    }
}
