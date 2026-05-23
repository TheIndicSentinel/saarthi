package com.saarthi.app.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.components.ChipTone
import com.saarthi.core.ui.components.RangoliDivider
import com.saarthi.core.ui.components.SaarthiChip
import com.saarthi.core.ui.components.SaarthiListRow
import com.saarthi.core.ui.components.SaarthiLogo
import com.saarthi.core.ui.components.SaarthiToggle
import com.saarthi.core.ui.components.SaarthiTopBar
import com.saarthi.core.ui.components.SectionLabel
import com.saarthi.core.ui.theme.DisplayAccent
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onChangeModel: () -> Unit,
    currentLanguage: com.saarthi.core.i18n.SupportedLanguage = com.saarthi.core.i18n.SupportedLanguage.HINDI,
    onChangeLanguage: (com.saarthi.core.i18n.SupportedLanguage) -> Unit = {},
    settingsViewModel: com.saarthi.app.SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    themeViewModel: com.saarthi.app.ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var showLangPicker by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val themeMode by themeViewModel.mode.collectAsStateWithLifecycle()
    val darkOn = themeMode == com.saarthi.core.ui.theme.ThemeMode.DARK
    // Daily wisdom notification — preference-backed (DataStore) and tied
    // to AlarmManager via WisdomNotificationScheduler. Previously this was
    // a `mutableStateOf` that did nothing; now the toggle actually
    // arms/cancels the 8 AM alarm.
    val wisdomVm: com.saarthi.app.wisdom.WisdomSettingsViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val notifOn by wisdomVm.enabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg),
    ) {
        SaarthiTopBar(title = "Settings", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfileCard()

            SectionLabel("AI & Models")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                title = "Active model",
                subtitle = "Choose & manage AI models",
                tone = ChipTone.Marigold,
                trailing = { ChevronRight() },
                onClick = onChangeModel,
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                title = "Manage downloads",
                subtitle = "Models stored on this device",
                tone = ChipTone.Indigo,
                trailing = { ChevronRight() },
                onClick = { onNavigate("downloads") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                title = "Response style",
                subtitle = "How Saarthi talks to you",
                tone = ChipTone.Terracotta,
                trailing = { ChevronRight() },
                onClick = { onNavigate("response-style") },
            )
            // Personality Pal — read-only summary; tapping opens chat where
            // the picker sheet lives (single source of truth for selection +
            // session reset).
            val settingsPersonaVm: com.saarthi.app.SettingsPersonalityViewModel =
                androidx.hilt.navigation.compose.hiltViewModel()
            val activePersona by settingsPersonaVm.active.collectAsStateWithLifecycle()
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Face, contentDescription = null) },
                title = "Persona",
                subtitle = "${activePersona.displayName} · ${activePersona.tagline}",
                tone = ChipTone.Indigo,
                trailing = { ChevronRight() },
                onClick = { onNavigate("assistant") },
            )

            SectionLabel("App")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                title = "Language",
                subtitle = "${currentLanguage.nativeName} · ${currentLanguage.englishName}",
                trailing = { ChevronRight() },
                onClick = { showLangPicker = true },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                title = "Chat history",
                trailing = { ChevronRight() },
                onClick = { onNavigate("history") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Notifications, null) },
                title = "Daily wisdom notifications",
                subtitle = if (notifOn) "A Sanskrit thought every morning at 8 AM"
                           else "Off — no daily notification",
                trailing = { SaarthiToggle(on = notifOn, onToggle = { wisdomVm.setEnabled(!notifOn) }) },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.DarkMode, null) },
                title = "Dark theme",
                subtitle = if (darkOn) "On — warm dark ink"
                           else "Off — daylight-friendly light",
                trailing = { SaarthiToggle(on = darkOn, onToggle = { themeViewModel.toggle() }) },
            )
            // Read replies aloud (TTS)
            val ttsVm: com.saarthi.app.TtsSettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val ttsOn by ttsVm.autoSpeak.collectAsStateWithLifecycle()
            SaarthiListRow(
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                },
                title = "Read replies aloud",
                subtitle = if (ttsOn) "Saarthi speaks each reply when it's ready"
                           else "Tap Listen on a reply to hear it",
                trailing = { SaarthiToggle(on = ttsOn, onToggle = { ttsVm.toggle() }) },
            )

            SectionLabel("Privacy")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                title = "Privacy details",
                subtitle = "100% on-device · Zero collection",
                tone = ChipTone.Jade,
                trailing = { ChevronRight() },
                onClick = { onNavigate("privacy") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                title = "Clear chat history",
                subtitle = "Permanently delete all conversations",
                tone = ChipTone.Rose,
                danger = true,
                trailing = { ChevronRight() },
                onClick = { showClearDialog = true },
            )

            SectionLabel("About")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                title = "About Saarthi",
                subtitle = "Version, credits, source code",
                tone = ChipTone.Marigold,
                trailing = { ChevronRight() },
                onClick = { onNavigate("about") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                title = "Rate Saarthi",
                subtitle = "Share your feedback",
                tone = ChipTone.Rose,
                trailing = { ChevronRight() },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                title = "Share with friends",
                trailing = { ChevronRight() },
            )

            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RangoliDivider(width = 100.dp, color = SaarthiColors.Text3)
                Spacer(Modifier.height(10.dp))
                Text(
                    "सर्वे भवन्तु सुखिनः",
                    style = DisplayAccent.copy(fontSize = 14.sp, color = SaarthiColors.Marigold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Made with care in India",
                    style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text4),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showLangPicker) {
        SettingsLanguageDialog(
            current = currentLanguage,
            onSelect = {
                onChangeLanguage(it)
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = SaarthiColors.Bg2,
            title = { Text("Clear chat history?", color = SaarthiColors.Text) },
            text = {
                Text(
                    "This permanently deletes every saved conversation across all sessions. The active model stays loaded.",
                    color = SaarthiColors.Text2,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    settingsViewModel.clearAllChatHistory()
                    showClearDialog = false
                }) {
                    Text("Delete all", color = SaarthiColors.Rose, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = SaarthiColors.Text2)
                }
            },
        )
    }
}

@Composable
private fun SettingsLanguageDialog(
    current: com.saarthi.core.i18n.SupportedLanguage,
    onSelect: (com.saarthi.core.i18n.SupportedLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaarthiColors.Bg2,
        title = {
            Text("Change language", color = SaarthiColors.Text)
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // `.entries` returns a cached EnumEntries — no need to .toList()
                // every recomposition, which was allocating a fresh ArrayList.
                items(com.saarthi.core.i18n.SupportedLanguage.entries) { lang ->
                    val isSel = lang == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) SaarthiColors.MarigoldSoft else SaarthiColors.Surface)
                            .border(1.dp, if (isSel) SaarthiColors.MarigoldBd else SaarthiColors.Border, RoundedCornerShape(12.dp))
                            .clickable { onSelect(lang) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lang.nativeName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = if (isSel) SaarthiColors.Marigold else SaarthiColors.Text,
                                ),
                            )
                            Text(
                                lang.englishName,
                                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                            )
                        }
                        if (isSel) {
                            Icon(
                                Icons.Filled.Check,
                                null,
                                tint = SaarthiColors.Marigold,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = SaarthiColors.Text2)
            }
        },
    )
}

@Composable
private fun ProfileCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SaarthiLogo(size = 52.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Saarthi",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Text(
                "Offline AI · v1.4",
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SaarthiChip(text = "Private", tone = ChipTone.Jade, small = true)
                SaarthiChip(text = "Offline", tone = ChipTone.Marigold, small = true)
            }
        }
    }
}

@Composable
private fun ChevronRight() {
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = SaarthiColors.Text4,
        modifier = Modifier.size(14.dp),
    )
}

// ── Privacy details ───────────────────────────────────────────────────────────

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Privacy", subtitle = "What stays on your device", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Big claim card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                SaarthiColors.Jade.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .border(1.dp, SaarthiColors.JadeBd.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    .padding(18.dp),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SaarthiColors.JadeSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Shield, null, tint = SaarthiColors.Jade, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing leaves your phone",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaarthiColors.Text,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Saarthi has no server. The AI lives on your device — questions are processed locally and never travel over the internet.",
                        style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2, fontSize = 13.sp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("Stored on this device only")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                title = "Your chat history",
                tone = ChipTone.Jade,
                trailing = { Text("Local", style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3)) },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                title = "AI model weights",
                tone = ChipTone.Jade,
                trailing = { Text("Local", style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3)) },
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel("How Saarthi works")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Bolt, null) },
                title = "Runs on your hardware",
                subtitle = "Gemma model, Vulkan / CPU inference",
                tone = ChipTone.Marigold,
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                title = "No accounts, no tracking",
                subtitle = "Works without internet",
                tone = ChipTone.Indigo,
            )
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.14f), Color.Transparent),
                    ),
                ),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            SaarthiTopBar(title = "About Saarthi", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SaarthiLogo(size = 84.dp)
                Spacer(Modifier.height(16.dp))
                Text("सारथी", style = DisplayAccent.copy(fontSize = 22.sp))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saarthi",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaarthiColors.Text,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "v 1.4.0 · build 187",
                    style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3),
                )
                Spacer(Modifier.height(16.dp))
                RangoliDivider(width = 120.dp, color = SaarthiColors.Marigold)
                Spacer(Modifier.height(16.dp))
                Text(
                    "A free, offline AI companion built for the next billion users — designed in India, for India.",
                    style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(22.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("10", "Languages", Modifier.weight(1f))
                    StatTile("4+", "AI models", Modifier.weight(1f))
                    StatTile("100%", "Offline", Modifier.weight(1f))
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Built with")
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SaarthiListRow(
                        leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                        title = "Google Gemma",
                        subtitle = "On-device language model",
                        trailing = { SaarthiChip(text = "Apache 2.0", small = true) },
                    )
                    SaarthiListRow(
                        leadingIcon = { Icon(Icons.Outlined.Bolt, null) },
                        title = "LiteRT",
                        subtitle = "Mobile inference runtime",
                        trailing = { SaarthiChip(text = "Apache 2.0", small = true) },
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "सर्वे भवन्तु सुखिनः",
                    style = DisplayAccent.copy(fontSize = 16.sp, color = SaarthiColors.Marigold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "\"May all be happy\" · Made in Bharat with care",
                    style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text4),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun StatTile(num: String, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            num,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Marigold,
            ),
        )
        Text(
            sub,
            style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3),
        )
    }
}

// ── Response style ────────────────────────────────────────────────────────────

@Composable
fun ResponseStyleScreen(
    onBack: () -> Unit,
    viewModel: com.saarthi.app.ResponseStyleViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val style by viewModel.style.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Response style", subtitle = "How Saarthi talks to you", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SegmentedCard(
                title = "Answer length",
                value = style.length,
                onChange = viewModel::setLength,
                options = listOf("short" to "Short", "medium" to "Medium", "long" to "Long"),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = "Tone",
                value = style.tone,
                onChange = viewModel::setTone,
                options = listOf("warm" to "Warm", "balanced" to "Balanced", "formal" to "Formal"),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = "Language style",
                value = style.languageMix,
                onChange = viewModel::setLanguageMix,
                options = listOf("pure" to "Pure", "mix" to "Hinglish", "eng" to "English"),
            )

            Spacer(Modifier.height(20.dp))
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                title = "Show disclaimers",
                subtitle = "Add safety notes for legal & medical topics",
                trailing = {
                    SaarthiToggle(
                        on = style.showDisclaimers,
                        onToggle = { viewModel.setShowDisclaimers(!style.showDisclaimers) },
                    )
                },
            )
            Spacer(Modifier.height(6.dp))
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                title = "Include examples",
                subtitle = "Anchor explanations with real-world cases",
                trailing = {
                    SaarthiToggle(
                        on = style.includeExamples,
                        onToggle = { viewModel.setIncludeExamples(!style.includeExamples) },
                    )
                },
            )
            Spacer(Modifier.height(20.dp))
            // Live preview — shows the user what a Saarthi reply will look
            // like under their current preferences. Pure UI, no model call.
            ResponseStylePreview(style)
            Spacer(Modifier.height(8.dp))
            Text(
                "Preferences are applied to every model you load.",
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ResponseStylePreview(style: com.saarthi.core.i18n.ResponseStyle) {
    val examplePrompt = "How do I make ginger tea for a sore throat?"
    val exampleReply = remember(style) { buildPreviewReply(style) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        SaarthiColors.Marigold.copy(alpha = 0.06f),
                        SaarthiColors.Terracotta.copy(alpha = 0.04f),
                    ),
                ),
            )
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            "PREVIEW",
            style = MaterialTheme.typography.labelSmall.copy(
                color = SaarthiColors.Marigold,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(10.dp))
        // Example user prompt
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                    .background(SaarthiColors.MarigoldSoft)
                    .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    examplePrompt,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = SaarthiColors.Text,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Example assistant reply
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp))
                .background(SaarthiColors.Surface)
                .border(1.dp, SaarthiColors.Border, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                exampleReply,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = SaarthiColors.Text,
                    lineHeight = 19.sp,
                ),
            )
        }
    }
}

private fun buildPreviewReply(style: com.saarthi.core.i18n.ResponseStyle): String {
    // Short replies for each axis combo. Constructed deterministically so a
    // preference change instantly shows a different result.
    val warmth = when (style.tone) {
        "warm"   -> "Sure thing — "
        "formal" -> "Certainly. "
        else     -> ""
    }
    val body = when (style.languageMix) {
        "pure" -> when (style.length) {
            "short" -> "अदरक उबाल कर शहद मिलाइए, गर्म पीजिए।"
            "long"  -> "एक कप पानी में अदरक की पाँच पतली स्लाइस डाल कर पाँच मिनट उबालें। थोड़ा छान लीजिए, स्वाद के लिए शहद और चुटकी भर हल्दी डालें। दिन में दो बार, चार दिन तक।"
            else    -> "एक कप पानी में अदरक उबाल कर शहद मिलाइए — दिन में दो बार लीजिए।"
        }
        "eng" -> when (style.length) {
            "short" -> "Boil ginger, add honey, sip warm."
            "long"  -> "Add 5 thin ginger slices to a cup of water. Simmer for 5 minutes, strain, stir in a teaspoon of honey and a pinch of turmeric. Sip twice a day for about four days."
            else    -> "Simmer ginger in water for 5 minutes, strain, then mix in honey. Drink twice daily."
        }
        else -> when (style.length) {
            "short" -> "Adrak boil karke shahad mila kar piyo — gale ko relief milega."
            "long"  -> "Ek cup paani mein 5 patli adrak slices daal kar 5 minute simmer karein. Strain karke ek chamach shahad aur chutki haldi mix karein. Din mein 2 baar, 3–4 din ke liye lein."
            else    -> "Adrak ko paani mein 5 minute boil karein, strain karke shahad mila lein. Din mein 2 baar lein."
        }
    }
    val example = if (style.includeExamples && style.length != "short")
        " For example: warm liquids loosen throat mucus, and honey coats the inflamed tissue."
    else ""
    val disclaimer = if (style.showDisclaimers && style.length != "short")
        " If symptoms last more than 3 days, please see a doctor."
    else ""
    return warmth + body + example + disclaimer
}

@Composable
private fun SegmentedCard(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    options: List<Pair<String, String>>,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(color = SaarthiColors.Text),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (k, label) ->
                val selected = value == k
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) SaarthiColors.MarigoldSoft else Color.Transparent)
                        .border(
                            1.dp,
                            if (selected) SaarthiColors.MarigoldBd else SaarthiColors.Border,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(onClick = { onChange(k) })
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = if (selected) SaarthiColors.Marigold else SaarthiColors.Text2,
                        ),
                    )
                }
            }
        }
    }
}

// ── Manage downloads ──────────────────────────────────────────────────────────

@Composable
fun ManageDownloadsScreen(
    onBack: () -> Unit,
    onAddModel: () -> Unit = {},
    viewModel: com.saarthi.app.ManageDownloadsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val installedBytes = remember(state.installed) { state.installed.sumOf { it.sizeBytes } }
    val totalGb = state.phoneTotalBytes / 1_073_741_824f
    val freeGb = state.phoneFreeBytes / 1_073_741_824f
    val saarthiGb = installedBytes / 1_073_741_824f
    val saarthiFrac = if (state.phoneTotalBytes > 0) installedBytes.toFloat() / state.phoneTotalBytes else 0f
    val otherFrac = if (state.phoneTotalBytes > 0)
        ((state.phoneTotalBytes - state.phoneFreeBytes - installedBytes).coerceAtLeast(0L)).toFloat() / state.phoneTotalBytes
    else 0f

    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(
            title = "Manage downloads",
            subtitle = "${state.installed.size} model${if (state.installed.size == 1) "" else "s"} · %.1f GB used".format(saarthiGb),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Phone storage card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SaarthiColors.Surface)
                    .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Text(
                    "PHONE STORAGE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SaarthiColors.Text3,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x0FF5EEE3)),
                ) {
                    if (saarthiFrac > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(saarthiFrac)
                                .fillMaxHeight()
                                .background(SaarthiColors.Marigold),
                        )
                    }
                    if (otherFrac > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(otherFrac)
                                .fillMaxHeight()
                                .background(Color(0x2EF5EEE3)),
                        )
                    }
                    val freeFrac = (1f - saarthiFrac - otherFrac).coerceAtLeast(0.001f)
                    Box(modifier = Modifier.weight(freeFrac).fillMaxHeight())
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StorageLegend(color = SaarthiColors.Marigold, label = "Saarthi models · %.1f GB".format(saarthiGb))
                    StorageLegend(color = Color(0x2EF5EEE3), label = "Other")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "%.1f GB free of %.0f GB total".format(freeGb, totalGb),
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text2),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "INSTALLED MODELS",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text3,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            if (state.installed.isEmpty()) {
                Text(
                    "No models downloaded yet.",
                    style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text3),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.installed.forEach { m ->
                        InstalledModelRow(
                            model = m,
                            onDelete = { viewModel.deleteModel(m.entry) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            // "Download more models" footer button — opens picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        androidx.compose.foundation.BorderStroke(1.dp, SaarthiColors.BorderHi),
                        RoundedCornerShape(16.dp),
                    )
                    .clickable(onClick = onAddModel)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    null,
                    tint = SaarthiColors.Text2,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Download more models",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = SaarthiColors.Text2,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StorageLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = SaarthiColors.Text3,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun InstalledModelRow(
    model: com.saarthi.app.DownloadedModel,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SaarthiColors.MarigoldSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Memory, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    model.entry.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(color = SaarthiColors.Text),
                )
                if (model.active) {
                    SaarthiChip(text = "Active", tone = ChipTone.Jade, small = true)
                }
            }
            Text(
                "%.2f GB".format(model.sizeBytes / 1_073_741_824f),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = SaarthiColors.Text3,
                    fontSize = 11.sp,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (model.active) Color(0x0AF5EEE3) else SaarthiColors.RoseSoft)
                .clickable(enabled = !model.active, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Delete,
                "Delete",
                tint = if (model.active) SaarthiColors.Text4 else SaarthiColors.Rose,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── History (placeholder — current chat session list lives in the assistant drawer) ──

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Conversations", subtitle = "All stored on your device", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Past conversations appear in the chat drawer (☰). This list view will land in a future update.",
                style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
            )
        }
    }
}
