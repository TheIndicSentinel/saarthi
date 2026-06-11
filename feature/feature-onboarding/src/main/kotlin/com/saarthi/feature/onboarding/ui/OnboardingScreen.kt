package com.saarthi.feature.onboarding.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.core.ui.components.AmbientGlow
import com.saarthi.core.ui.components.ChipTone
import com.saarthi.core.ui.components.RangoliDivider
import com.saarthi.core.ui.components.SaarthiChip
import com.saarthi.core.ui.components.SaarthiLogo
import com.saarthi.core.ui.components.SaarthiPrimaryButton
import com.saarthi.core.ui.theme.DisplayAccent
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.SaarthiDisplayFont
import com.saarthi.feature.onboarding.viewmodel.OnboardingStep
import com.saarthi.feature.onboarding.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) onOnboardingComplete()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.onModelUriPicked(context, uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.Bg),
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onb-step",
        ) { step ->
            when (step) {
                OnboardingStep.SPLASH -> SplashScreen(onContinue = viewModel::goToWelcome)
                OnboardingStep.WELCOME -> Onb1Welcome(
                    onNext = viewModel::goToLanguageSelect,
                    onSkip = { /* no skip from welcome */ },
                )
                OnboardingStep.LANGUAGE_SELECT -> Onb2Language(
                    selected = state.selectedLanguage,
                    onSelect = viewModel::selectLanguage,
                    onNext = viewModel::goToPrivacy,
                    onBack = viewModel::goToWelcome,
                )
                OnboardingStep.PRIVACY -> Onb3Privacy(
                    onNext = viewModel::proceedToModelPick,
                    onBack = viewModel::goToLanguageSelect,
                )
                OnboardingStep.MODEL_PICK -> Onb4ModelPick(
                    state = state,
                    onDownload = viewModel::downloadModel,
                    onCancel = viewModel::cancelDownload,
                    onSelect = viewModel::selectDownloadedModel,
                    onHighlight = viewModel::highlightModel,
                    onDelete = viewModel::deleteModel,
                    onProceed = viewModel::proceedFromModelPick,
                    onBack = viewModel::goToPrivacy,
                )
                OnboardingStep.DOWNLOADING,
                OnboardingStep.MODEL_INIT -> DownloadingScreen(
                    state = state,
                    onBack = { viewModel.goBackTo(OnboardingStep.MODEL_PICK) },
                )
                OnboardingStep.CHAT_TEST -> SetupCompleteScreen(
                    onContinue = viewModel::completeOnboarding,
                )
                OnboardingStep.DONE -> {}
            }
        }
    }
}

// ── Splash ─────────────────────────────────────────────────────────────────────

@Composable
private fun SplashScreen(onContinue: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800)
        onContinue()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(420.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.22f), Color.Transparent),
                    ),
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SaarthiLogo(size = 88.dp)
            Spacer(Modifier.height(24.dp))
            Text("सारथी", style = DisplayAccent.copy(fontSize = 18.sp, color = SaarthiColors.Marigold))
            Spacer(Modifier.height(4.dp))
            Text(
                "Saarthi",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your AI companion · आपका साथी",
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text3),
            )
            Spacer(Modifier.height(48.dp))
            RangoliDivider(width = 140.dp, color = SaarthiColors.Marigold)
            Spacer(Modifier.height(16.dp))
            Text(
                "OFFLINE · PRIVATE · FREE",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text4,
                    letterSpacing = 1.4.sp,
                ),
            )
        }
        Text(
            "Powered by Gemma · On-device AI",
            style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text4),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}

// ── Shared shell ──────────────────────────────────────────────────────────────

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .height(5.dp)
                    .width(if (active) 22.dp else 5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) SaarthiColors.Marigold else Color(0x2EF5EEE3)),
            )
        }
    }
}

@Composable
private fun OnbStepShell(
    stepIdx: Int,
    total: Int,
    onBack: () -> Unit,
    onSkip: (() -> Unit)? = null,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // ambient glow
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(420.dp)
                .padding(top = 0.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
        )
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (stepIdx > 0) {
                    IconCircle(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SaarthiColors.Text, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Box(Modifier.size(36.dp))
                }
                ProgressDots(stepIdx, total)
                if (onSkip != null) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = SaarthiColors.Text3,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onSkip)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                } else {
                    Box(Modifier.size(36.dp))
                }
            }
            Box(modifier = Modifier.weight(1f)) { content() }
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SaarthiPrimaryButton(
                    text = primaryLabel,
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            null,
                            tint = SaarthiColors.OnMarigold,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun IconCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x10F5EEE3))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// ── Onb1 — Welcome ────────────────────────────────────────────────────────────

@Composable
private fun Onb1Welcome(onNext: () -> Unit, onSkip: () -> Unit) {
    OnbStepShell(
        stepIdx = 0,
        total = 4,
        onBack = {},
        onSkip = null,
        primaryLabel = "Get Started",
        onPrimary = onNext,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SaarthiLogo(size = 84.dp)
            Spacer(Modifier.height(18.dp))
            Text("सारथी", style = DisplayAccent.copy(fontSize = 20.sp))
            Spacer(Modifier.height(10.dp))
            Text(
                "Your private AI,",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
                textAlign = TextAlign.Center,
            )
            Text(
                "made for India.",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Marigold,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            // Outcome-first: show what Saarthi actually DOES before the model
            // download. Users care about results, not "an AI companion".
            Column(modifier = Modifier.fillMaxWidth()) {
                WelcomeOutcome("📄", "Ask questions about your PDFs & documents")
                WelcomeOutcome("🌾", "Kisan helper — crops, schemes, mandi prices")
                WelcomeOutcome("🎙️", "Voice — speak your question, hear the answer")
                WelcomeOutcome("🔒", "Works offline · nothing leaves your phone")
            }
        }
    }
}

@Composable
private fun WelcomeOutcome(emoji: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SaarthiColors.Text2,
                fontSize = 14.sp,
            ),
        )
    }
}

// ── Onb2 — Language ───────────────────────────────────────────────────────────

@Composable
private fun Onb2Language(
    selected: SupportedLanguage,
    onSelect: (SupportedLanguage) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    OnbStepShell(
        stepIdx = 1,
        total = 4,
        onBack = onBack,
        onSkip = null,
        primaryLabel = "Continue",
        onPrimary = onNext,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Choose your language",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "अपनी भाषा चुनें · You can change this anytime",
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
            )
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SupportedLanguage.entries) { lang ->
                    LanguageRow(lang = lang, selected = lang == selected, onClick = { onSelect(lang) })
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(lang: SupportedLanguage, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) SaarthiColors.MarigoldSoft else SaarthiColors.Surface
    val borderColor = if (selected) SaarthiColors.MarigoldBd else SaarthiColors.Border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) SaarthiColors.MarigoldBd else Color(0x0DF5EEE3)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                lang.nativeName.take(1),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SaarthiDisplayFont,
                    fontSize = 16.sp,
                    color = if (selected) SaarthiColors.Marigold else SaarthiColors.Text2,
                ),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                lang.nativeName,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = if (selected) SaarthiColors.Marigold else SaarthiColors.Text,
                ),
            )
            Text(
                lang.englishName,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
        if (selected) {
            Icon(Icons.Default.Check, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Onb3 — Privacy ────────────────────────────────────────────────────────────

@Composable
private fun Onb3Privacy(onNext: () -> Unit, onBack: () -> Unit) {
    OnbStepShell(
        stepIdx = 2,
        total = 4,
        onBack = onBack,
        onSkip = null,
        primaryLabel = "I understand",
        onPrimary = onNext,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(SaarthiColors.JadeSoft, Color.Transparent),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.JadeSoft)
                        .border(1.dp, SaarthiColors.JadeBd, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Shield, null, tint = SaarthiColors.Jade, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Private by design",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Saarthi runs the AI on your phone. Your questions, files, and answers never leave your device.",
                style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PrivacyRow(Icons.Outlined.WifiOff, "No internet needed", "Works on a flight, in a village, anywhere")
            Spacer(Modifier.height(10.dp))
            PrivacyRow(Icons.Outlined.Lock, "Zero data collection", "No accounts, no tracking, no servers")
            Spacer(Modifier.height(10.dp))
            PrivacyRow(Icons.Outlined.Memory, "Runs on your hardware", "Google Gemma model, optimized for mobile")
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PrivacyRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SaarthiColors.JadeSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = SaarthiColors.Jade, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(color = SaarthiColors.Text))
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3))
        }
    }
}

// ── Onb4 — Model Pick ─────────────────────────────────────────────────────────

@Composable
private fun Onb4ModelPick(
    state: com.saarthi.feature.onboarding.viewmodel.OnboardingUiState,
    onDownload: (ModelEntry) -> Unit,
    onCancel: (ModelEntry) -> Unit,
    onSelect: (ModelEntry) -> Unit,
    onHighlight: (ModelEntry) -> Unit,
    onDelete: (ModelEntry) -> Unit,
    onProceed: () -> Unit,
    onBack: () -> Unit,
) {
    val pickedReady = state.catalogModels.any {
        state.selectedModelPath?.endsWith(it.fileName) == true && it.id in state.downloadedModelIds
    }
    OnbStepShell(
        stepIdx = 3,
        total = 4,
        onBack = onBack,
        onSkip = null,
        primaryLabel = if (pickedReady) "Continue" else "Download & Continue",
        onPrimary = onProceed,
        primaryEnabled = state.catalogModels.isNotEmpty(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Pick your AI brain",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Download once · Works offline forever",
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
            )
            Spacer(Modifier.height(14.dp))
            DeviceTierBadge(profile = state.deviceProfile)
            Spacer(Modifier.height(14.dp))
            state.catalogModels.forEachIndexed { i, model ->
                val progress = state.downloadProgress[model.id]
                val isDownloaded = model.id in state.downloadedModelIds
                ModelOption(
                    model = model,
                    progress = progress,
                    selected = state.selectedModelPath?.endsWith(model.fileName) == true,
                    downloaded = isDownloaded,
                    onClick = {
                        if (isDownloaded) onSelect(model) else onHighlight(model)
                    },
                    onCancel = { onCancel(model) },
                    onDelete = { onDelete(model) },
                    toneIndex = i,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Rose),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceTierBadge(profile: DeviceProfile?) {
    if (profile == null) return
    val ram = "${profile.totalRamMb / 1024}GB RAM"
    val label = when (profile.tier) {
        DeviceTier.FLAGSHIP -> "Flagship · $ram · ${if (profile.hasVulkan) "Vulkan GPU" else "CPU"}"
        DeviceTier.MID      -> "Mid-range · $ram"
        DeviceTier.LOW      -> "Entry · $ram"
        DeviceTier.MINIMAL  -> "Ultra-low · $ram"
    }
    // Honest, plain-language expectation for THIS phone. Setting it before the
    // download decision is what keeps a mid/low-RAM user from picking the
    // heaviest model, getting slow/blank replies, and leaving a 1-star review.
    val expectation = when (profile.tier) {
        DeviceTier.FLAGSHIP -> "Runs the best models smoothly."
        DeviceTier.MID      -> "Pick the recommended model for the best balance of speed and quality."
        DeviceTier.LOW      -> "Choose a lighter model for smooth replies — bigger ones may run slowly."
        DeviceTier.MINIMAL  -> "Only the compact model will run well here; replies stay short and simple."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.MarigoldSoft)
            .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Memory, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SaarthiColors.Text,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                expectation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = SaarthiColors.Text2,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

@Composable
private fun ModelOption(
    model: ModelEntry,
    progress: DownloadProgress?,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    toneIndex: Int,
) {
    val tone = when (toneIndex % 4) {
        0 -> ChipTone.Marigold
        1 -> ChipTone.Jade
        2 -> ChipTone.Indigo
        else -> ChipTone.Terracotta
    }
    val tag = model.tags.firstOrNull() ?: "Model"

    val bg = if (selected) SaarthiColors.MarigoldSoft else SaarthiColors.Surface
    val borderColor = if (selected) SaarthiColors.MarigoldBd else SaarthiColors.Border

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // radio dot — pinned to title baseline
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (selected) SaarthiColors.Marigold else SaarthiColors.BorderHi, CircleShape)
                    .background(if (selected) SaarthiColors.Marigold else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SaarthiColors.Bg))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                // Title row — title + the one tag chip. Fixed-height regardless
                // of download state, so all model cards align.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        model.displayName,
                        // weight(fill=false) lets the name take the space left by
                        // the chip and wrap on narrow screens, instead of starving
                        // the chip into a squeezed vertical sliver (which also blew
                        // up the row height on small devices).
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SaarthiColors.Text,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SaarthiChip(text = tag, tone = tone, small = true)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SaarthiColors.Text3,
                        fontSize = 11.5.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${model.fileSizeMb} MB",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = SaarthiColors.Text3,
                            fontSize = 11.sp,
                        ),
                    )
                    // Status text — always present so row height is uniform.
                    val status = when {
                        progress is DownloadProgress.Downloading -> "· Downloading ${progress.percent}%"
                        progress is DownloadProgress.Paused -> "· Paused"
                        progress is DownloadProgress.Failed -> "· Failed"
                        downloaded -> "· Ready to use"
                        else -> "· Not downloaded"
                    }
                    val statusColor = when {
                        progress is DownloadProgress.Downloading -> SaarthiColors.Marigold
                        progress is DownloadProgress.Failed -> SaarthiColors.Rose
                        downloaded -> SaarthiColors.Jade
                        else -> SaarthiColors.Text3
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = statusColor,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
        if (progress is DownloadProgress.Downloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                color = SaarthiColors.Marigold,
                trackColor = Color(0x0FF5EEE3),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${progress.percent}% · ${progress.bytesDownloaded / 1_048_576}MB / ${progress.totalBytes / 1_048_576}MB",
                style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3),
            )
        }
        if (progress is DownloadProgress.Paused) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Paused · ${progress.reason}",
                style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Marigold),
            )
        }
        if (progress is DownloadProgress.Failed) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Download failed: ${progress.reason}",
                style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Rose),
            )
        }
    }
}

// ── Downloading screen (also covers MODEL_INIT) ───────────────────────────────

@Composable
private fun DownloadingScreen(
    state: com.saarthi.feature.onboarding.viewmodel.OnboardingUiState,
    onBack: () -> Unit,
) {
    // Pick the model currently downloading or being initialized
    val activeProgress = state.downloadProgress.entries.firstOrNull {
        it.value is DownloadProgress.Downloading
    }
    val activeModel = state.catalogModels.firstOrNull { it.id == activeProgress?.key }
        ?: state.catalogModels.firstOrNull { state.selectedModelPath?.endsWith(it.fileName) == true }
        ?: state.catalogModels.firstOrNull()

    val downloading = activeProgress?.value as? DownloadProgress.Downloading
    val pct = downloading?.percent ?: if (state.isLoading) 100 else 0
    val isInit = state.isLoading

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(420.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.20f), Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SaarthiColors.Text, modifier = Modifier.size(18.dp))
                }
                ProgressDots(3, 4)
                Box(Modifier.size(36.dp))
            }
            Spacer(Modifier.weight(0.1f))
            SaarthiLogo(size = 156.dp, progress = (pct / 100f).coerceIn(0f, 1f))
            Spacer(Modifier.height(28.dp))
            Text(
                if (isInit) "INITIALIZING" else "DOWNLOADING",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text3,
                    letterSpacing = 1.4.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                activeModel?.displayName ?: "AI Model",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isInit) "Loading weights into memory…"
                else "Setting up your AI brain. After this, Saarthi works fully offline — forever.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SaarthiColors.Text2,
                    fontSize = 13.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            // Progress card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SaarthiColors.Surface)
                    .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SaarthiColors.Marigold,
                        ),
                    )
                    if (downloading != null) {
                        Text(
                            "${downloading.bytesDownloaded / 1_048_576} / ${downloading.totalBytes / 1_048_576} MB",
                            style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = SaarthiColors.Marigold,
                    trackColor = Color(0x0FF5EEE3),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Tip: You can keep using your phone — we'll finish in the background.",
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp, start = 8.dp, end = 8.dp),
            )
        }
    }
}

// ── Setup Complete ────────────────────────────────────────────────────────────

@Composable
private fun SetupCompleteScreen(onContinue: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800)
        onContinue()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(420.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.30f), Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Concentric rings around the mandala mark
            Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(160.dp).clip(CircleShape).border(1.dp, SaarthiColors.MarigoldBd.copy(alpha = 0.4f), CircleShape))
                Box(modifier = Modifier.size(120.dp).clip(CircleShape).border(1.dp, SaarthiColors.MarigoldBd.copy(alpha = 0.6f), CircleShape))
                SaarthiLogo(size = 96.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "स्वागत है",
                style = DisplayAccent.copy(fontSize = 20.sp, color = SaarthiColors.Marigold),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ready, Saarthi",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your AI companion is set up and waiting. From here on, everything happens on your device.",
                style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
                textAlign = TextAlign.Center,
            )
        }
    }
}
