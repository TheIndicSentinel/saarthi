package com.saarthi.feature.onboarding.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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

    if (state.step == OnboardingStep.DONE) {
        onOnboardingComplete()
        return
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
                    onNext = viewModel::proceedToModelInit,
                )
                OnboardingStep.MODEL_INIT -> ModelInitStep(isLoading = state.isLoading, error = state.error)
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
        Text(
            "Saarthi",
            style = MaterialTheme.typography.displayLarge,
            color = SaarthiColors.Gold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your trusted offline AI guide.\nPrivate. Always available.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = SaarthiColors.TextSecondary,
        )
        Spacer(Modifier.height(48.dp))
        SaarthiPrimaryButton(
            text = "Get Started",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
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
        Text(
            "Choose your language",
            style = MaterialTheme.typography.headlineMedium,
            color = SaarthiColors.Gold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "भाषा चुनें • மொழியை தேர்ந்தெடுக்கவும்",
            style = MaterialTheme.typography.bodyMedium,
            color = SaarthiColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
        SaarthiPrimaryButton(
            text = "Continue",
            onClick = onNext,
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
