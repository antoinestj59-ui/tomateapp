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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import tomate.app.BuildConfig
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.ThemeMode
import tomate.core.designsystem.theme.TomateSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    relayEnabled: Boolean,
    onRelayEnabledChange: (Boolean) -> Unit,
    batterySaver: Boolean,
    onBatterySaverChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Réglages") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
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
                        Text("Apparence", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.s))
                        ThemeMode.entries.forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = themeMode == mode,
                                    onClick = { onThemeModeChange(mode) },
                                )
                                Text(
                                    text = when (mode) {
                                        ThemeMode.System -> "Suivre le système"
                                        ThemeMode.Light -> "Thème clair"
                                        ThemeMode.Dark -> "Thème sombre"
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(TomateSpacing.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Couleurs dynamiques", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Android 12+. Désactivé pour conserver l'identité TOMATE.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                        }
                    }
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Text("Proximité et relais", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Relais multi-appareils", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Transmet des enveloppes opaques, TTL 24 h maximum, quotas stricts.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = relayEnabled, onCheckedChange = onRelayEnabledChange)
                        }
                        Spacer(Modifier.height(TomateSpacing.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Économie de batterie", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Réduit la découverte et la fréquence des transferts.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = batterySaver, onCheckedChange = onBatterySaverChange)
                        }
                    }
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Text("Confidentialité", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = "Aucune publicité. Aucune localisation précise demandée. " +
                                "Les données restent prioritairement sur l'appareil ; seuls les contenus que vous " +
                                "publiez ou envoyez sont transmis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                TomateCard {
                    Column(modifier = Modifier.padding(TomateSpacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("À propos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            MetricPill(text = "v${BuildConfig.VERSION_NAME}")
                        }
                        Spacer(Modifier.height(TomateSpacing.xs))
                        Text(
                            text = "TOMATE ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(TomateSpacing.s))
                        TomateInlineNotice(
                            text = "État du chiffrement de bout en bout : non opérationnel. " +
                                "Les messages bénéficient de TLS vers le serveur, pas d'un chiffrement de bout en bout vérifié.",
                            tone = NoticeTone.Warning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
