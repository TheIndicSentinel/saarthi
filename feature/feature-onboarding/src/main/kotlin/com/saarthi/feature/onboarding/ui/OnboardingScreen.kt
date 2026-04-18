package com.saarthi.feature.onboarding.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.ui.components.GlassmorphicCard
import com.saarthi.core.ui.components.SaarthiPrimaryButton
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.onboarding.viewmodel.OnboardingStep
import com.saarthi.feature.onboarding.viewmodel.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (state.step == OnboardingStep.DONE) {
        onOnboardingComplete()
        return
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.onModelUriPicked(context, uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding_step",
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onNext = viewModel::goToLanguageSelect)
                OnboardingStep.LANGUAGE_SELECT -> LanguageSelectStep(
                    selectedLanguage = state.selectedLanguage,
                    onSelect = viewModel::selectLanguage,
                    onNext = viewModel::proceedToModelPick,
                )
                OnboardingStep.MODEL_PICK -> ModelPickStep(
                    candidates = state.modelCandidates,
                    selectedPath = state.selectedModelPath,
                    isScanning = state.isScanning,
                    error = state.error,
                    onSelect = viewModel::selectModel,
                    onBrowse = { filePicker.launch(arrayOf("*/*")) },
                    onConfirm = viewModel::confirmModelAndInit,
                )
                OnboardingStep.MODEL_INIT -> ModelInitStep(
                    isLoading = state.isLoading,
                    error = state.error,
                )
                OnboardingStep.DONE -> {}
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🪔", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(24.dp))
        Text("Saarthi", style = MaterialTheme.typography.displayLarge, color = SaarthiColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your trusted offline AI guide.\nPrivate. Always available.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = SaarthiColors.TextSecondary,
        )
        Spacer(Modifier.height(48.dp))
        SaarthiPrimaryButton(text = "Get Started", onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LanguageSelectStep(
    selectedLanguage: SupportedLanguage,
    onSelect: (SupportedLanguage) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Choose your language", style = MaterialTheme.typography.headlineMedium, color = SaarthiColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text("भाषा चुनें • மொழியை தேர்ந்தெடுக்கவும்", style = MaterialTheme.typography.bodyMedium, color = SaarthiColors.TextMuted)
        Spacer(Modifier.height(24.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(SupportedLanguage.entries) { lang ->
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = if (lang == selectedLanguage) SaarthiColors.Gold else SaarthiColors.GlassBorder,
                    onClick = { onSelect(lang) },
                ) {
                    Text(
                        "${lang.flag}  ${lang.nativeName}  •  ${lang.englishName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (lang == selectedLanguage) SaarthiColors.Gold else SaarthiColors.TextPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SaarthiPrimaryButton(text = "Continue", onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ModelPickStep(
    candidates: List<String>,
    selectedPath: String?,
    isScanning: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onBrowse: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Select AI Model", style = MaterialTheme.typography.headlineMedium, color = SaarthiColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Place your Gemma .bin file anywhere on the device.\nWe'll find it automatically, or browse to select it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = SaarthiColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))

        if (isScanning) {
            CircularProgressIndicator(color = SaarthiColors.Gold, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Scanning device…", color = SaarthiColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        } else if (candidates.isEmpty()) {
            Text("No model files detected automatically.", color = SaarthiColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(candidates) { path ->
                val isSelected = path == selectedPath
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = if (isSelected) SaarthiColors.Gold else SaarthiColors.GlassBorder,
                    onClick = { onSelect(path) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = SaarthiColors.Gold, modifier = Modifier.size(18.dp))
                        Text(
                            path.substringAfterLast("/"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = SaarthiColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Browse for model file", color = SaarthiColors.TextPrimary)
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = SaarthiColors.Error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(12.dp))
        SaarthiPrimaryButton(
            text = if (selectedPath != null || candidates.isNotEmpty()) "Load Model" else "Select a model to continue",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModelInitStep(isLoading: Boolean, error: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = SaarthiColors.Gold)
            Spacer(Modifier.height(24.dp))
            Text("Loading AI model…", style = MaterialTheme.typography.bodyLarge, color = SaarthiColors.TextSecondary)
        }
        if (error != null) {
            Text("Error: $error", color = SaarthiColors.Error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
