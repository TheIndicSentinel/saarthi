package com.saarthi.feature.onboarding.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.saarthi.core.ui.components.GlassmorphicCard
import com.saarthi.core.ui.components.SaarthiPrimaryButton
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.onboarding.viewmodel.OnboardingStep
import com.saarthi.feature.onboarding.viewmodel.OnboardingViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
                    deviceProfile = state.deviceProfile,
                    catalogModels = state.catalogModels,
                    downloadProgress = state.downloadProgress,
                    localCandidates = state.modelCandidates,
                    selectedPath = state.selectedModelPath,
                    isScanning = state.isScanning,
                    error = state.error,
                    onDownload = viewModel::downloadModel,
                    onCancelDownload = viewModel::cancelDownload,
                    onSelectDownloaded = viewModel::selectDownloadedModel,
                    onSelectLocal = viewModel::selectModel,
                    onBrowse = { filePicker.launch(arrayOf("*/*")) },
                    onConfirm = viewModel::confirmModelAndInit,
                    onGrantAllFiles = { viewModel.openAllFilesAccessSettings(context) },
                    onRescan = viewModel::rescanAfterPermissionGrant,
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

// ── Welcome ───────────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.07f))

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

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OnboardingFeatureRow("🔒", "Complete Privacy", "Your conversations never leave your device", SaarthiColors.Gold)
            OnboardingFeatureRow("📴", "100% Offline", "Works without internet, anywhere in India", SaarthiColors.CyberTeal)
            OnboardingFeatureRow("🪔", "Made for Bharat", "Understands Indian languages and context", SaarthiColors.Saffron)
        }

        Spacer(Modifier.weight(0.08f))

        SaarthiPrimaryButton(
            text = "शुरू करें — Get Started",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Open-source AI · Runs entirely on your device",
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(0.04f))
    }
}

// ── Language select ───────────────────────────────────────────────────────────

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

// ── Model pick ────────────────────────────────────────────────────────────────

@Composable
private fun ModelPickStep(
    deviceProfile: DeviceProfile?,
    catalogModels: List<ModelEntry>,
    downloadProgress: Map<String, DownloadProgress>,
    localCandidates: List<String>,
    selectedPath: String?,
    isScanning: Boolean,
    error: String?,
    onDownload: (ModelEntry) -> Unit,
    onCancelDownload: (ModelEntry) -> Unit,
    onSelectDownloaded: (ModelEntry) -> Unit,
    onSelectLocal: (String) -> Unit,
    onBrowse: () -> Unit,
    onConfirm: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRescan: () -> Unit,
) {
    var showLocalSection by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Select AI Model",
            style = MaterialTheme.typography.headlineMedium,
            color = SaarthiColors.Gold,
        )
        Spacer(Modifier.height(6.dp))

        // Device badge
        if (deviceProfile != null) {
            val tierLabel = when (deviceProfile.tier) {
                DeviceTier.FLAGSHIP -> "Flagship · ${deviceProfile.totalRamMb / 1024}GB RAM · ${if (deviceProfile.hasVulkan) "Vulkan GPU" else "CPU"}"
                DeviceTier.MID      -> "Mid-range · ${deviceProfile.totalRamMb / 1024}GB RAM"
                DeviceTier.LOW      -> "Entry-level · ${deviceProfile.totalRamMb / 1024}GB RAM"
            }
            val tierColor = when (deviceProfile.tier) {
                DeviceTier.FLAGSHIP -> SaarthiColors.Gold
                DeviceTier.MID      -> SaarthiColors.CyberTeal
                DeviceTier.LOW      -> SaarthiColors.TextSecondary
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(tierColor.copy(alpha = 0.12f))
                    .border(1.dp, tierColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("📱", style = MaterialTheme.typography.bodySmall)
                Text(tierLabel, style = MaterialTheme.typography.labelMedium, color = tierColor)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Catalog models ────────────────────────────────────────────────────
        Text(
            "Recommended models for your device",
            style = MaterialTheme.typography.titleSmall,
            color = SaarthiColors.TextSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        catalogModels.forEach { model ->
            val progress = downloadProgress[model.id]
            CatalogModelCard(
                model = model,
                progress = progress,
                isSelected = selectedPath?.endsWith(model.fileName) == true,
                onDownload = { onDownload(model) },
                onCancel = { onCancelDownload(model) },
                onSelect = { onSelectDownloaded(model) },
            )
            Spacer(Modifier.height(10.dp))
        }

        // ── Divider / toggle for existing files ───────────────────────────────
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { showLocalSection = !showLocalSection },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (showLocalSection) "Hide existing files ▲" else "Use a model already on this device ▼",
                color = SaarthiColors.TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        AnimatedVisibility(visible = showLocalSection) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SaarthiColors.GlassBorder)
                Spacer(Modifier.height(12.dp))

                if (isScanning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = SaarthiColors.Gold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Scanning device…", color = SaarthiColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (localCandidates.isEmpty()) {
                    Text("No model files found automatically.", color = SaarthiColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    localCandidates.forEach { path ->
                        val isSelected = path == selectedPath
                        GlassmorphicCard(
                            modifier = Modifier.fillMaxWidth(),
                            accentColor = if (isSelected) SaarthiColors.Gold else SaarthiColors.GlassBorder,
                            onClick = { onSelectLocal(path) },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = SaarthiColors.Gold, modifier = Modifier.size(16.dp))
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

                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
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
            }
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = SaarthiColors.Error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        SaarthiPrimaryButton(
            text = if (selectedPath != null) "Load Selected Model" else "Select or download a model",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CatalogModelCard(
    model: ModelEntry,
    progress: DownloadProgress?,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
) {
    val accentColor = when {
        isSelected -> SaarthiColors.Gold
        model.tags.contains("Recommended") -> SaarthiColors.CyberTeal.copy(alpha = 0.6f)
        else -> SaarthiColors.GlassBorder
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.NavyLight)
            .border(1.dp, accentColor, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextPrimary,
                    )
                }
                if (model.tags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        model.tags.take(2).forEach { tag ->
                            val tagColor = when (tag) {
                                "Recommended" -> SaarthiColors.Gold
                                "Best Quality" -> SaarthiColors.CyberTeal
                                else -> SaarthiColors.TextMuted
                            }
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = tagColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tagColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // Right action area
            when {
                isSelected -> {
                    Icon(Icons.Default.CheckCircle, null, tint = SaarthiColors.Gold, modifier = Modifier.size(28.dp))
                }
                progress is DownloadProgress.Completed -> {
                    TextButton(onClick = onSelect) {
                        Text("Use", color = SaarthiColors.Gold, style = MaterialTheme.typography.labelMedium)
                    }
                }
                progress is DownloadProgress.Downloading -> {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Cancel", tint = SaarthiColors.Error, modifier = Modifier.size(20.dp))
                    }
                }
                else -> {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SaarthiColors.Gold.copy(alpha = 0.15f)),
                    ) {
                        Icon(Icons.Default.CloudDownload, "Download", tint = SaarthiColors.Gold, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            model.description,
            style = MaterialTheme.typography.bodySmall,
            color = SaarthiColors.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Size info
        Spacer(Modifier.height(4.dp))
        Text(
            "${model.fileSizeMb} MB · ${model.engineType.name.replace('_', ' ')}",
            style = MaterialTheme.typography.labelSmall,
            color = SaarthiColors.TextMuted,
        )

        // Download progress bar
        if (progress is DownloadProgress.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = SaarthiColors.Gold,
                trackColor = SaarthiColors.GlassBorder,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${progress.percent}% · ${progress.bytesDownloaded / 1_048_576}MB / ${progress.totalBytes / 1_048_576}MB",
                style = MaterialTheme.typography.labelSmall,
                color = SaarthiColors.TextMuted,
            )
        }

        if (progress is DownloadProgress.Failed) {
            Spacer(Modifier.height(4.dp))
            Text("Download failed: ${progress.reason}", color = SaarthiColors.Error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Model init ────────────────────────────────────────────────────────────────

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
            Spacer(Modifier.height(8.dp))
            Text(
                "This may take 30–60 seconds on first load.",
                style = MaterialTheme.typography.bodySmall,
                color = SaarthiColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (error != null) {
            Text("Error: $error", color = SaarthiColors.Error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Setup complete ────────────────────────────────────────────────────────────

@Composable
private fun SetupCompleteStep(onComplete: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        onComplete()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
            Icon(Icons.Default.CheckCircle, null, tint = SaarthiColors.Gold, modifier = Modifier.size(52.dp))
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
        CircularProgressIndicator(color = SaarthiColors.Gold, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

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
    accentColor: Color = SaarthiColors.Gold,
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
