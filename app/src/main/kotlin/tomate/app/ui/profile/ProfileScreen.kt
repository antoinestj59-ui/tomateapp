package tomate.app.ui.profile

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.BuildConfig
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateMenuRow
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateRingAvatar
import tomate.core.designsystem.components.TomateSectionHeader
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.compactCount
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.Visibility
import tomate.core.model.label

/**
 * Profil, présenté comme dans les maquettes : en-tête centré, statistiques réelles,
 * accès rapides, puis rubriques utiles et déconnexion.
 *
 * Toutes les entrées mènent à un écran ou à une boîte de dialogue fonctionnelle ; les
 * compteurs proviennent du profil et du fil local, jamais d'une valeur d'exemple.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    viewModel: ProfileViewModel = viewModel(factory = tomateViewModelFactory { ProfileViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var recoveryOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val displayName = state.profile?.displayName?.ifBlank { state.profile?.handle.orEmpty() } ?: "Profil non configuré"
    val handle = state.profile?.let { "@${it.handle}" } ?: "—"

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Profil", fontWeight = FontWeight.Bold) },
            actions = {
                Box(
                    modifier = Modifier
                        .padding(end = TomateSpacing.s)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = TomateSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(TomateSpacing.xs),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        TomateRingAvatar(label = displayName, size = 104, ringWidth = 3)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = viewModel::startEditing),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Modifier le profil",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(TomateSpacing.s))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.profile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(TomateSpacing.s))
                    state.profile?.visibility?.let { MetricPill(text = it.label) }
                }
            }

            if (state.error != null) {
                item {
                    TomateInlineNotice(
                        text = state.error!!.userMessage,
                        tone = NoticeTone.Error,
                        modifier = Modifier.padding(horizontal = TomateSpacing.l),
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s),
                ) {
                    TomatePrimaryButton(
                        text = "Modifier le profil",
                        onClick = viewModel::startEditing,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Mon profil TOMATE : ${state.profile?.let { "@${it.handle}" } ?: displayName}",
                                    )
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(share, "Partager mon profil"))
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Partager mon profil")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Stat(value = compactCount(state.myPostCount), label = "Publications")
                    Stat(value = compactCount(state.myCommunityCount), label = "Communautés")
                    Stat(value = compactCount(state.blocked.size), label = "Bloqués")
                }
            }

            if (state.userId?.startsWith("local-") == true) {
                item {
                    TomateInlineNotice(
                        text = "Compte local : créé hors connexion. Il sera relié automatiquement à Supabase dès " +
                            "qu'une connexion sera disponible, sans perte de vos publications ni de vos messages.",
                        tone = NoticeTone.Info,
                        modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.xs),
                    )
                }
            }

            item { TomateSectionHeader(title = "Mon compte") }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.Settings,
                    title = "Paramètres",
                    subtitle = "Apparence, réseau, stockage",
                    onClick = onOpenSettings,
                )
            }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.NotificationsNone,
                    title = "Notifications",
                    subtitle = "Messages, réactions, adhésions",
                    onClick = onOpenNotifications,
                )
            }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.Lock,
                    title = "Confidentialité",
                    subtitle = "Visibilité du profil, comptes bloqués",
                    onClick = { privacyOpen = true },
                )
            }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.VpnKey,
                    title = "Code de récupération",
                    subtitle = state.recoveryStatus,
                    onClick = { recoveryOpen = true },
                )
            }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.Block,
                    title = "Chiffrement",
                    subtitle = state.e2eeLabel,
                    onClick = { privacyOpen = true },
                )
            }

            item { TomateSectionHeader(title = "À propos") }
            item {
                TomateMenuRow(
                    icon = Icons.Filled.Lock,
                    title = "À propos de TOMATE",
                    trailingText = "v${BuildConfig.VERSION_NAME}",
                    onClick = { aboutOpen = true },
                )
            }
            item {
                Text(
                    text = "Identifiant de compte : ${state.userId ?: "aucune session"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
                )
            }
            item {
                Text(
                    text = "Opérations en attente : ${state.pendingOperations}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l),
                )
            }

            item {
                Spacer(Modifier.height(TomateSpacing.m))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TomateSpacing.l)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { confirmSignOut = true }
                        .padding(vertical = TomateSpacing.m),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(TomateSpacing.s))
                    Text(
                        text = "Se déconnecter",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(TomateSpacing.xs))
                Text(
                    text = "La déconnexion supprime la session et le profil local de cet appareil. " +
                        "Sans code de récupération, le compte anonyme n'est plus accessible.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TomateSpacing.l),
                )
            }
        }
    }

    if (privacyOpen) {
        AlertDialog(
            onDismissRequest = { privacyOpen = false },
            title = { Text("Confidentialité") },
            text = {
                Column {
                    Text(
                        text = "Visibilité du profil : ${state.profile?.visibility?.label ?: "non configurée"}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    Text(
                        text = "Chiffrement des messages : ${state.e2eeLabel}. TOMATE n'annonce pas d'E2EE " +
                            "tant qu'un protocole éprouvé n'est pas intégré et vérifié.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.blocked.isNotEmpty()) {
                        Spacer(Modifier.height(TomateSpacing.m))
                        Text("Comptes bloqués", style = MaterialTheme.typography.titleSmall)
                        state.blocked.forEach { blocked ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
            },
            confirmButton = {
                TextButton(onClick = { privacyOpen = false }) { Text("Fermer") }
            },
        )
    }

    if (recoveryOpen) {
        AlertDialog(
            onDismissRequest = { recoveryOpen = false },
            title = { Text("Code de récupération") },
            text = {
                Column {
                    Text(text = state.recoveryStatus, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(TomateSpacing.s))
                    Text(
                        text = "Le code est stocké chiffré sur cet appareil. La restauration sur un autre appareil " +
                            "nécessite une fonction serveur qui n'est pas déployée : elle est donc affichée comme " +
                            "non disponible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.recoveryCode != null) {
                        Spacer(Modifier.height(TomateSpacing.s))
                        TomateInlineNotice(
                            text = "Notez ce code maintenant, il ne sera plus affiché : ${state.recoveryCode}",
                            tone = NoticeTone.Info,
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (state.recoveryCode != null) {
                        TextButton(onClick = viewModel::clearRecoveryDisplay) { Text("J'ai noté") }
                    } else {
                        TextButton(onClick = viewModel::generateRecoveryCode) { Text("Générer") }
                    }
                    TextButton(onClick = { recoveryOpen = false }) { Text("Fermer") }
                }
            },
        )
    }

    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("TOMATE") },
            text = {
                Column {
                    Text(
                        text = "TOMATE ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(TomateSpacing.s))
                    Text(
                        text = "Réseau social et messagerie, avec un backend Supabase protégé par RLS. " +
                            "Aucune donnée de démonstration n'est affichée : tout ce que vous voyez vient de " +
                            "votre compte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("Fermer") } },
        )
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

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
