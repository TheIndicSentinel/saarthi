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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
                    manualPathInput = state.manualPathInput,
                    needsAllFilesPermission = state.needsAllFilesPermission,
                    onSelect = viewModel::selectModel,
                    onBrowse = { filePicker.launch(arrayOf("*/*")) },
                    onConfirm = viewModel::confirmModelAndInit,
                    onManualPathChange = viewModel::onManualPathChange,
                    onSelectManualPath = viewModel::selectModelByManualPath,
                    onGrantAllFiles = { viewModel.openAllFilesAccessSettings(context) },
                    onRescan = viewModel::rescanAfterPermissionGrant,
                    onCheckPermission = { viewModel.checkAndRequestAllFilesAccess(context) },
                )
                OnboardingStep.MODEL_INIT -> ModelInitStep(
                    isLoading = state.isLoading,
                    error = state.error,
                )
                OnboardingStep.CHAT_TEST -> ChatTestStep(
                    testInput = state.testInput,
                    testResponse = state.testResponse,
                    isTestLoading = state.isTestLoading,
                    onInputChange = viewModel::onTestInputChange,
                    onSend = viewModel::sendTestMessage,
                    onComplete = viewModel::completeOnboarding,
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
    manualPathInput: String,
    needsAllFilesPermission: Boolean,
    onSelect: (String) -> Unit,
    onBrowse: () -> Unit,
    onConfirm: () -> Unit,
    onManualPathChange: (String) -> Unit,
    onSelectManualPath: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRescan: () -> Unit,
    onCheckPermission: () -> Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Select AI Model", style = MaterialTheme.typography.headlineMedium, color = SaarthiColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select your Gemma .bin model file from Downloads or any folder.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = SaarthiColors.TextMuted,
        )
        Spacer(Modifier.height(16.dp))

        // Auto-scan results
        if (isScanning) {
            CircularProgressIndicator(color = SaarthiColors.Gold, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Scanning device…", color = SaarthiColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        } else if (candidates.isEmpty()) {
            Text("No model files found automatically.", color = SaarthiColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }

        if (!isScanning && candidates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            candidates.forEach { path ->
                val isSelected = path == selectedPath
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = if (isSelected) SaarthiColors.Gold else SaarthiColors.GlassBorder,
                    onClick = { onSelect(path) },
                ) {
                    // Column inside Box (GlassmorphicCard uses BoxScope) to prevent overlap
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isSelected) Icon(
                                Icons.Default.CheckCircle, null,
                                tint = SaarthiColors.Gold,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                path.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            path.substringBeforeLast("/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = SaarthiColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Browse for model file", color = SaarthiColors.TextPrimary)
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onGrantAllFiles, modifier = Modifier.fillMaxWidth()) {
            Text("Grant All Files Access (then rescan)", color = SaarthiColors.TextPrimary)
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Rescan after granting access", color = SaarthiColors.TextPrimary)
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = SaarthiColors.Error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(12.dp))
        SaarthiPrimaryButton(
            text = if (selectedPath != null) "Load Model" else "Select a model to continue",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun ChatTestStep(
    testInput: String,
    testResponse: String?,
    isTestLoading: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onComplete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Test Your Model", style = MaterialTheme.typography.headlineMedium, color = SaarthiColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Send a message to verify the model is working correctly.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = SaarthiColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))

        if (testResponse != null) {
            GlassmorphicCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                accentColor = SaarthiColors.GlassBorder,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                ) {
                    Text(
                        "Saarthi:",
                        style = MaterialTheme.typography.labelMedium,
                        color = SaarthiColors.Gold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        testResponse,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SaarthiColors.TextPrimary,
                    )
                }
            }
        } else if (isTestLoading) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = SaarthiColors.Gold)
                    Spacer(Modifier.height(12.dp))
                    Text("Thinking…", color = SaarthiColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = testInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Ask something…", color = SaarthiColors.TextMuted)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SaarthiColors.Gold,
                    unfocusedBorderColor = SaarthiColors.GlassBorder,
                    focusedTextColor = SaarthiColors.TextPrimary,
                    unfocusedTextColor = SaarthiColors.TextPrimary,
                ),
                singleLine = true,
                enabled = !isTestLoading,
            )
            IconButton(
                onClick = onSend,
                enabled = testInput.isNotBlank() && !isTestLoading,
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = SaarthiColors.Gold)
            }
        }

        Spacer(Modifier.height(16.dp))
        SaarthiPrimaryButton(
            text = "Continue to Saarthi",
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
