package com.saarthi.feature.onboarding.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
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
                OnboardingStep.CHAT_TEST -> SetupCompleteStep(
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.07f))

        // Sacred geometry art with brand text overlay
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            WelcomeMandala(modifier = Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "सारथी",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                    ),
                    color = SaarthiColors.Gold,
                )
                Text(
                    "SAARTHI",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 6.sp),
                    color = SaarthiColors.TextMuted,
                )
            }
        }

        Spacer(Modifier.weight(0.05f))

        Text(
            "आपका निजी AI सहायक",
            style = MaterialTheme.typography.titleLarge,
            color = SaarthiColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Private · Offline · Always There",
            style = MaterialTheme.typography.bodyMedium,
            color = SaarthiColors.TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(0.07f))

        // Feature list
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OnboardingFeatureRow(
                icon = "🔒",
                title = "Complete Privacy",
                subtitle = "Your conversations never leave your device",
                accentColor = SaarthiColors.Gold,
            )
            OnboardingFeatureRow(
                icon = "📴",
                title = "100% Offline",
                subtitle = "Works without internet, anywhere in India",
                accentColor = SaarthiColors.CyberTeal,
            )
            OnboardingFeatureRow(
                icon = "🪔",
                title = "Made for Bharat",
                subtitle = "Understands Indian languages and context",
                accentColor = SaarthiColors.Saffron,
            )
        }

        Spacer(Modifier.weight(0.08f))

        SaarthiPrimaryButton(
            text = "शुरू करें — Get Started",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Powered by Google Gemma 2B · Runs entirely on your device",
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(0.04f))
    }
}

@Composable
private fun WelcomeMandala(modifier: Modifier = Modifier) {
    val gold = SaarthiColors.Gold
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r1 = size.width * 0.47f
        val r2 = size.width * 0.36f
        val r3 = size.width * 0.24f
        val sw = 1.2f

        drawCircle(color = gold.copy(0.10f), radius = r1, center = Offset(cx, cy), style = Stroke(sw))
        drawCircle(color = gold.copy(0.25f), radius = r2, center = Offset(cx, cy), style = Stroke(sw * 1.5f))
        drawCircle(color = gold.copy(0.45f), radius = r3, center = Offset(cx, cy), style = Stroke(sw * 2f))

        repeat(8) { i ->
            val angle = i * (PI / 4)
            drawLine(
                color = gold.copy(0.08f),
                start = Offset((cx + r3 * cos(angle)).toFloat(), (cy + r3 * sin(angle)).toFloat()),
                end = Offset((cx + r1 * cos(angle)).toFloat(), (cy + r1 * sin(angle)).toFloat()),
                strokeWidth = sw,
            )
        }

        repeat(8) { i ->
            val angle = i * (PI / 4) + (PI / 8)
            drawCircle(
                color = gold.copy(0.60f),
                radius = 3.5f,
                center = Offset((cx + r2 * cos(angle)).toFloat(), (cy + r2 * sin(angle)).toFloat()),
            )
        }

        // 4 corner accents on outer ring
        repeat(4) { i ->
            val angle = i * (PI / 2) + (PI / 4)
            drawLine(
                color = gold.copy(0.20f),
                start = Offset((cx + (r1 - 12f) * cos(angle)).toFloat(), (cy + (r1 - 12f) * sin(angle)).toFloat()),
                end = Offset((cx + (r1 + 12f) * cos(angle)).toFloat(), (cy + (r1 + 12f) * sin(angle)).toFloat()),
                strokeWidth = sw * 2f,
            )
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: String,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color = SaarthiColors.Gold,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.NavyLight)
            .border(1.dp, SaarthiColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = SaarthiColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SaarthiColors.TextMuted)
        }
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
private fun SetupCompleteStep(onComplete: () -> Unit) {
    // Auto-navigate after 1.5s so user sees the success state briefly
    LaunchedEffect(Unit) {
        delay(1500)
        onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SaarthiColors.Gold.copy(alpha = 0.12f))
                .border(1.dp, SaarthiColors.Gold.copy(alpha = 0.4f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SaarthiColors.Gold,
                modifier = Modifier.size(52.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            "सारथी तैयार है",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = SaarthiColors.Gold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your AI is ready — offline, private, always with you.",
            style = MaterialTheme.typography.bodyLarge,
            color = SaarthiColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        CircularProgressIndicator(
            color = SaarthiColors.Gold,
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
        )
    }
}
