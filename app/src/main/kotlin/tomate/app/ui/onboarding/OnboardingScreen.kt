package tomate.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tomate.app.ui.vm.tomateViewModelFactory
import tomate.core.designsystem.components.MetricPill
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.components.TomatePrimaryButton
import tomate.core.designsystem.components.TomateTextField
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.designsystem.theme.TomateTheme
import tomate.core.model.Visibility
import tomate.core.model.label

/**
 * Onboarding: TOMATE identity in one step.
 * It states explicitly that the account is anonymous and that the handle alone cannot recover it.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = tomateViewModelFactory { OnboardingViewModel(it) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TomateSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "TOMATE", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(TomateSpacing.s))
        Text(
            text = "Communiquez même sans Internet. Votre identifiant de compte est un identifiant anonyme : " +
                "votre pseudo ne suffit pas à récupérer un compte.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(TomateSpacing.xl))

        TomateTextField(
            value = state.handle,
            onValueChange = viewModel::onHandleChange,
            label = "Pseudo",
            supportingText = when {
                state.checkingHandle -> "Vérification de la disponibilité…"
                state.verificationDeferred ->
                    "Hors connexion : le pseudo sera vérifié en ligne plus tard, sans bloquer la création."
                state.handleAvailable == true -> "Ce pseudo est disponible."
                state.handleAvailable == false -> "Ce pseudo est déjà pris."
                else -> "3 à 20 caractères : lettres minuscules, chiffres ou _."
            },
            isError = state.handleAvailable == false,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(TomateSpacing.m))
        TomateTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = "Nom affiché",
            supportingText = "Facultatif, modifiable à tout moment.",
        )
        Spacer(Modifier.height(TomateSpacing.m))
        TomateTextField(
            value = state.bio,
            onValueChange = viewModel::onBioChange,
            label = "Bio (facultatif)",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
        )

        Spacer(Modifier.height(TomateSpacing.l))
        Text(text = "Visibilité du profil", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(TomateSpacing.s))
        VisibilityChooser(selected = state.visibility, onSelect = viewModel::onVisibilityChange)

        Spacer(Modifier.height(TomateSpacing.l))
        if (state.offline) {
            TomateInlineNotice(
                text = "Hors connexion : votre compte sera créé sur cet appareil, immédiatement utilisable. " +
                    "Il sera relié au réseau automatiquement dès qu'une connexion sera disponible.",
                tone = NoticeTone.Info,
            )
            Spacer(Modifier.height(TomateSpacing.s))
        }
        TomateInlineNotice(
            text = "Compte anonyme : sans code de récupération enregistré, la perte de l'appareil signifie la perte du compte. " +
                "La récupération n'est pas encore opérationnelle dans cette version.",
            tone = NoticeTone.Warning,
        )

        Spacer(Modifier.height(TomateSpacing.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.acceptedTerms, onCheckedChange = viewModel::onTermsChange)
            Column {
                Text(
                    text = "J'accepte les règles de base : pas de harcèlement, pas de contenu illégal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Aucune publicité. Aucune localisation demandée.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(TomateSpacing.m))
            TomateInlineNotice(
                text = state.error!!.userMessage,
                tone = NoticeTone.Error,
            )
        }

        Spacer(Modifier.height(TomateSpacing.l))
        TomatePrimaryButton(
            text = if (state.offline) "Créer mon compte hors ligne" else "Créer mon compte anonyme",
            onClick = { viewModel.submit(onCompleted) },
            modifier = Modifier.fillMaxWidth(),
            loading = state.submitting,
            enabled = state.handle.isNotBlank(),
        )
        Spacer(Modifier.height(TomateSpacing.m))
        MetricPill(
            text = if (state.offline) {
                "Compte local : aucune connexion requise"
            } else {
                "Vos données restent lisibles même hors ligne"
            },
        )
    }
}

@Composable
private fun VisibilityChooser(selected: Visibility, onSelect: (Visibility) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TomateSpacing.xs)) {
        Visibility.entries.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                )
                Column {
                    Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (option) {
                            Visibility.Public -> "Visible par tous les comptes connectés."
                            Visibility.Followers -> "Visible par vos abonnés."
                            Visibility.Community -> "Visible dans vos communautés."
                            Visibility.Private -> "Visible uniquement par vous."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
