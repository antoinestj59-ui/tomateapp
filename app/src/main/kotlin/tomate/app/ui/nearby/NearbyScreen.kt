package tomate.app.ui.nearby

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.TomateApplication
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateEmptyState
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomateNetworkBadge
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.TomateTonalButton
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.NetworkStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    network: NetworkStatus,
    relayEnabled: Boolean,
    batterySaver: Boolean,
    viewModel: NearbyViewModel = viewModel(
        factory = tomateViewModelFactory { container ->
            NearbyViewModel(TomateApplication.instance, container)
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionsResult(result.values.all { it })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Proximité") },
            actions = { TomateNetworkBadge(status = network) },
        )

        LazyColumn(
            contentPadding = PaddingValues(TomateSpacing.l),
            verticalArrangement = Arrangement.spacedBy(TomateSpacing.m),
        ) {
            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (state.sharing) "Partage actif" else "Partage inactif",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            MetricPill(text = "Bluetooth : ${state.bleAvailability.label()}")
                        }
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = "Wi-Fi local : ${state.wifiAvailability.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(TomateSpacing.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(TomateSpacing.s)) {
                            if (state.sharing) {
                                TomateTonalButton(text = "Arrêter", onClick = viewModel::stopSharing)
                            } else {
                                TomatePrimaryButton(
                                    text = "Activer le partage",
                                    onClick = {
                                        val missing = viewModel.requiredPermissions()
                                        if (missing.isEmpty()) viewModel.startSharing()
                                        else permissionLauncher.launch(missing.toTypedArray())
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(TomateSpacing.s))
                        Text(
                            text = "La découverte est explicite et limitée : aucun scan permanent, arrêt immédiat possible. " +
                                "Le partage dépend des permissions, de la batterie et de la portée radio.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Relais multi-appareils", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Transmet uniquement des enveloppes opaques, TTL 24 h, ${state.relayQueue} en file.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = relayEnabled, onCheckedChange = viewModel::setRelayEnabled)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mode économie de batterie", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Réduit la fréquence de découverte.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = batterySaver, onCheckedChange = viewModel::setBatterySaver)
                        }
                    }
                }
            }

            if (state.error != null) {
                item {
                    TomateInlineNotice(text = state.error!!.userMessage, tone = NoticeTone.Error)
                }
            }

            item {
                TomateTextField(
                    value = state.composingText,
                    onValueChange = viewModel::onComposingChange,
                    label = "Message à envoyer en proximité",
                    supportingText = "Choisissez ensuite un appareil ci-dessous pour l'envoyer réellement.",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
            }

            if (state.peers.isEmpty()) {
                item {
                    TomateEmptyState(
                        icon = Icons.Filled.Radar,
                        title = "Aucun appareil détecté",
                        message = if (state.sharing) {
                            "Aucun appareil TOMATE à portée pour l'instant. La découverte continue tant que le partage est actif."
                        } else {
                            "Activez le partage pour découvrir les appareils TOMATE à proximité."
                        },
                        modifier = Modifier.height(260.dp),
                    )
                }
            } else {
                items(state.peers, key = { "${it.peer.transport}-${it.peer.peerId}" }) { peer ->
                    TomateCard {
                        Row(
                            modifier = Modifier.padding(TomateSpacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.peer.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "${peer.transportLabel} · ${peer.peer.rssi?.let { "$it dBm" } ?: "portée inconnue"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (peer.peer.paired) {
                                MetricPill(text = "Appairé")
                            } else {
                                IconButton(onClick = { viewModel.pair(peer) }) {
                                    Icon(Icons.Filled.Radar, contentDescription = "Appairer")
                                }
                            }
                            IconButton(
                                onClick = { viewModel.send(peer) },
                                enabled = state.composingText.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
                            }
                        }
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                item {
                    Column {
                        Text("Journal", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.xs))
                        state.log.forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.received.isNotEmpty()) {
                item {
                    Column {
                        Text("Messages reçus en proximité", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.xs))
                        state.received.forEach { (text, transport) ->
                            TomateCard {
                                Column(modifier = Modifier.padding(TomateSpacing.m)) {
                                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "via $transport",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(TomateSpacing.xs))
                        }
                    }
                }
            }
        }
    }
}

private fun tomate.net.transport.TransportAvailability.label(): String = when (this) {
    tomate.net.transport.TransportAvailability.Ready -> "actif"
    tomate.net.transport.TransportAvailability.Stopped -> "inactif"
    tomate.net.transport.TransportAvailability.PermissionMissing -> "autorisation requise"
    tomate.net.transport.TransportAvailability.Unsupported -> "non pris en charge"
    tomate.net.transport.TransportAvailability.Disabled -> "radio désactivée"
    tomate.net.transport.TransportAvailability.Unknown -> "inconnu"
}
