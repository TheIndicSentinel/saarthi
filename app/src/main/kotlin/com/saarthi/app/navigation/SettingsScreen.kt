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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
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
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.settings
import com.saarthi.core.i18n.settingsDetail
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
    val s = currentLanguage.settings
    val context = androidx.compose.ui.platform.LocalContext.current
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
        SaarthiTopBar(title = s.settings, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfileCard()

            // Saarthi Pro upsell — prominent, top of the list. Hidden in v1:
            // Pro ships off until Google Play Billing is wired (showing a
            // purchase flow with no real billing is a Play rejection). Flip
            // FeatureFlags.PRO_ENABLED when billing is integrated.
            if (com.saarthi.core.i18n.FeatureFlags.PRO_ENABLED) {
                SaarthiListRow(
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                    title = "Saarthi Pro",
                    subtitle = "Unlock documents, voice & memory · Founder ₹199",
                    tone = ChipTone.Marigold,
                    trailing = { ChevronRight() },
                    onClick = { onNavigate("pro") },
                )
            }

            SectionLabel(s.sectionAiModels)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                title = s.activeModel,
                subtitle = s.activeModelSub,
                tone = ChipTone.Marigold,
                trailing = { ChevronRight() },
                onClick = onChangeModel,
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                title = s.manageDownloads,
                subtitle = s.manageDownloadsSub,
                tone = ChipTone.Indigo,
                trailing = { ChevronRight() },
                onClick = { onNavigate("downloads") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                title = s.responseStyle,
                subtitle = s.responseStyleSub,
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
                title = s.persona,
                subtitle = "${activePersona.displayName} · ${activePersona.tagline}",
                tone = ChipTone.Indigo,
                trailing = { ChevronRight() },
                onClick = { onNavigate("assistant") },
            )

            SectionLabel(s.sectionApp)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                title = s.language,
                subtitle = "${currentLanguage.nativeName} · ${currentLanguage.englishName}",
                trailing = { ChevronRight() },
                onClick = { showLangPicker = true },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Notifications, null) },
                title = s.dailyWisdom,
                subtitle = if (notifOn) s.wisdomOn else s.wisdomOff,
                trailing = { SaarthiToggle(on = notifOn, onToggle = { wisdomVm.setEnabled(!notifOn) }) },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.DarkMode, null) },
                title = s.darkTheme,
                subtitle = if (darkOn) s.darkOn else s.darkOff,
                trailing = { SaarthiToggle(on = darkOn, onToggle = { themeViewModel.toggle() }) },
            )
            // Read replies aloud (TTS) — hands-free auto-read is a Pro feature;
            // manual "Listen" on a reply stays free for everyone.
            val ttsVm: com.saarthi.app.TtsSettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val ttsOn by ttsVm.autoSpeak.collectAsStateWithLifecycle()
            val ttsIsPro by ttsVm.isPro.collectAsStateWithLifecycle()
            if (ttsIsPro) {
                SaarthiListRow(
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    },
                    title = s.readAloud,
                    subtitle = if (ttsOn) "Saarthi speaks each reply when it's ready"
                               else "Tap Listen on a reply to hear it",
                    trailing = { SaarthiToggle(on = ttsOn, onToggle = { ttsVm.toggle() }) },
                )
            } else {
                SaarthiListRow(
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    },
                    title = s.readAloud,
                    subtitle = "Saarthi Pro — auto-read every reply, hands-free",
                    tone = ChipTone.Marigold,
                    trailing = { ProChip() },
                    onClick = { onNavigate("pro") },
                )
            }

            SectionLabel(s.sectionPrivacy)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                title = s.privacyDetails,
                subtitle = s.privacyDetailsSub,
                tone = ChipTone.Jade,
                trailing = { ChevronRight() },
                onClick = { onNavigate("privacy") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                title = s.memoryTitle,
                subtitle = s.memoryTitleSub,
                tone = ChipTone.Jade,
                trailing = { ChevronRight() },
                onClick = { onNavigate("memory") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                title = s.clearHistory,
                subtitle = s.clearHistorySub,
                tone = ChipTone.Rose,
                danger = true,
                trailing = { ChevronRight() },
                onClick = { showClearDialog = true },
            )

            SectionLabel(s.sectionAbout)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                title = s.aboutSaarthi,
                subtitle = s.aboutSaarthiSub,
                tone = ChipTone.Marigold,
                trailing = { ChevronRight() },
                onClick = { onNavigate("about") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) },
                title = s.helpSupport,
                subtitle = s.helpSupportSub,
                tone = ChipTone.Jade,
                trailing = { ChevronRight() },
                onClick = { onNavigate("support") },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                title = s.rateSaarthi,
                subtitle = s.rateSaarthiSub,
                tone = ChipTone.Rose,
                trailing = { ChevronRight() },
                // Open the Play Store listing (in-app Play if installed, else web).
                onClick = {
                    val pkg = context.packageName
                    val market = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=$pkg"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(market) }.onFailure {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                title = s.shareFriends,
                trailing = { ChevronRight() },
                // System share sheet with the Play Store link.
                onClick = {
                    val pkg = context.packageName
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            "${currentLanguage.shareAppMessage}\nhttps://play.google.com/store/apps/details?id=$pkg",
                        )
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, null)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
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
                    s.madeWithCare,
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
            title = { Text(s.clearDialogTitle, color = SaarthiColors.Text) },
            text = {
                Text(
                    s.clearDialogBody,
                    color = SaarthiColors.Text2,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    settingsViewModel.clearAllChatHistory()
                    showClearDialog = false
                }) {
                    Text(s.deleteAll, color = SaarthiColors.Rose, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text(s.cancel, color = SaarthiColors.Text2)
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
    val s = current.settings
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaarthiColors.Bg2,
        title = {
            Text(s.changeLanguage, color = SaarthiColors.Text)
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
                Text(s.cancel, color = SaarthiColors.Text2)
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

/** Small "PRO" badge shown on rows for Pro-only features. */
@Composable
private fun ProChip() {
    Text(
        "PRO",
        style = MaterialTheme.typography.labelSmall.copy(
            color = SaarthiColors.OnMarigold,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SaarthiColors.Marigold)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ── Privacy details ───────────────────────────────────────────────────────────

@Composable
fun PrivacyScreen(onBack: () -> Unit, currentLanguage: SupportedLanguage = SupportedLanguage.HINDI) {
    val d = currentLanguage.settingsDetail
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = d.privacyTitle, subtitle = d.privacyTopSub, onBack = onBack)
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
                        d.privacyHeroTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaarthiColors.Text,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        d.privacyHeroBody,
                        style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2, fontSize = 13.sp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(d.privacyStoredHere)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                title = d.privacyChatHistory,
                tone = ChipTone.Jade,
                trailing = { Text(d.privacyLocal, style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3)) },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                title = d.privacyModelWeights,
                tone = ChipTone.Jade,
                trailing = { Text(d.privacyLocal, style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3)) },
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel(d.privacyHowWorks)
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Bolt, null) },
                title = d.privacyRunsHardware,
                subtitle = d.privacyRunsHardwareSub,
                tone = ChipTone.Marigold,
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                title = d.privacyNoAccounts,
                subtitle = d.privacyNoAccountsSub,
                tone = ChipTone.Indigo,
            )
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────

@Composable
fun AboutScreen(onBack: () -> Unit, currentLanguage: SupportedLanguage = SupportedLanguage.HINDI) {
    val d = currentLanguage.settingsDetail
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
            SaarthiTopBar(title = d.aboutTitle, onBack = onBack)
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
                    d.aboutTagline,
                    style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(22.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("10", d.statLanguages, Modifier.weight(1f))
                    StatTile("4+", d.statModels, Modifier.weight(1f))
                    StatTile("100%", d.statOffline, Modifier.weight(1f))
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel(d.aboutBuiltWith)
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SaarthiListRow(
                        leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                        title = "Google Gemma",
                        subtitle = d.aboutGemmaSub,
                        trailing = { SaarthiChip(text = "Apache 2.0", small = true) },
                    )
                    SaarthiListRow(
                        leadingIcon = { Icon(Icons.Outlined.Bolt, null) },
                        title = "LiteRT",
                        subtitle = d.aboutLiteRtSub,
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
                    d.aboutBlessing,
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
    currentLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    viewModel: com.saarthi.app.ResponseStyleViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    genViewModel: com.saarthi.app.GenerationSettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val d = currentLanguage.settingsDetail
    val style by viewModel.style.collectAsStateWithLifecycle()
    val temperature by genViewModel.temperature.collectAsStateWithLifecycle()
    val isAutoTemp by genViewModel.isAuto.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = d.rsTitle, subtitle = d.rsTopSub, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SegmentedCard(
                title = d.rsAnswerLength,
                value = style.length,
                onChange = viewModel::setLength,
                options = listOf("short" to d.optShort, "medium" to d.optMedium, "long" to d.optLong),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = d.rsTone,
                value = style.tone,
                onChange = viewModel::setTone,
                options = listOf("warm" to d.optWarm, "balanced" to d.optBalanced, "formal" to d.optFormal),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = d.rsLanguageStyle,
                value = style.languageMix,
                onChange = viewModel::setLanguageMix,
                options = listOf("pure" to d.optPure, "mix" to d.optHinglish, "eng" to d.optEnglish),
            )

            Spacer(Modifier.height(14.dp))
            CreativityCard(
                d = d,
                temperature = temperature,
                isAuto = isAutoTemp,
                onPreset = genViewModel::setTemperature,
                onSlide = genViewModel::setTemperature,
                onReset = genViewModel::resetToAuto,
            )

            Spacer(Modifier.height(20.dp))
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                title = d.rsShowDisclaimers,
                subtitle = d.rsShowDisclaimersSub,
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
                title = d.rsIncludeExamples,
                subtitle = d.rsIncludeExamplesSub,
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
            ResponseStylePreview(style, d.rsPreview, currentLanguage)
            Spacer(Modifier.height(8.dp))
            Text(
                d.rsAppliedNote,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ResponseStylePreview(
    style: com.saarthi.core.i18n.ResponseStyle,
    previewLabel: String,
    language: SupportedLanguage,
) {
    val examplePrompt = when (language) {
        SupportedLanguage.HINDI   -> "गले की खराश के लिए अदरक की चाय कैसे बनाएँ?"
        SupportedLanguage.MARATHI -> "घसा खवखवल्यास आल्याचा चहा कसा बनवायचा?"
        else -> "How do I make ginger tea for a sore throat?"
    }
    val exampleReply = remember(style, language) { buildPreviewReply(style, language) }
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
            previewLabel,
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

private fun buildPreviewReply(
    style: com.saarthi.core.i18n.ResponseStyle,
    language: SupportedLanguage,
): String {
    // Deterministic sample so a preference change instantly shows a new result.
    // Rendered in the UI language: languageMix "eng" → English; "pure" → native
    // (no English words); else (हिंग्लिश/mix) → native script with light English
    // loanwords. Only Hindi & Marathi have native sample text — other languages
    // fall back to English (avoids showing Hindi to e.g. a Tamil user) until
    // their language pass adds samples.
    val mr = language == SupportedLanguage.MARATHI
    val native = language == SupportedLanguage.HINDI || mr
    val useEng = style.languageMix == "eng" || !native
    val pure = style.languageMix == "pure"

    val warmth = when {
        useEng -> when (style.tone) { "warm" -> "Sure thing — "; "formal" -> "Certainly. "; else -> "" }
        mr     -> when (style.tone) { "warm" -> "नक्कीच — "; "formal" -> "नक्कीच. "; else -> "" }
        else   -> when (style.tone) { "warm" -> "ज़रूर — "; "formal" -> "जी ज़रूर। "; else -> "" }
    }
    val body = when {
        useEng -> when (style.length) {
            "short" -> "Boil ginger, add honey, sip warm."
            "long"  -> "Add 5 thin ginger slices to a cup of water. Simmer for 5 minutes, strain, stir in a teaspoon of honey and a pinch of turmeric. Sip twice a day for about four days."
            else    -> "Simmer ginger in water for 5 minutes, strain, then mix in honey. Drink twice daily."
        }
        pure && mr -> when (style.length) {
            "short" -> "आले उकळून मध मिसळा, कोमट प्या."
            "long"  -> "एक कप पाण्यात आल्याचे पाच पातळ काप घालून पाच मिनिटे उकळा. गाळून घ्या, चवीसाठी मध आणि चिमूटभर हळद घाला. दिवसातून दोनदा, चार दिवस."
            else    -> "एक कप पाण्यात आले उकळून मध मिसळा — दिवसातून दोनदा घ्या."
        }
        pure -> when (style.length) {
            "short" -> "अदरक उबाल कर शहद मिलाइए, गर्म पीजिए।"
            "long"  -> "एक कप पानी में अदरक की पाँच पतली स्लाइस डाल कर पाँच मिनट उबालें। थोड़ा छान लीजिए, स्वाद के लिए शहद और चुटकी भर हल्दी डालें। दिन में दो बार, चार दिन तक।"
            else    -> "एक कप पानी में अदरक उबाल कर शहद मिलाइए — दिन में दो बार लीजिए।"
        }
        mr -> when (style.length) {
            "short" -> "आले पाण्यात boil करून मध मिसळा — घशाला relief मिळेल."
            "long"  -> "एक कप पाण्यात आल्याचे 5 पातळ slices घालून 5 मिनिटे simmer करा. गाळून एक चमचा मध आणि चिमूटभर हळद mix करा. दिवसातून 2 वेळा, 3–4 दिवस घ्या."
            else    -> "आले पाण्यात 5 मिनिटे boil करा, गाळून मध मिसळा. दिवसातून 2 वेळा घ्या."
        }
        else -> when (style.length) {
            "short" -> "अदरक को पानी में boil करके शहद मिलाएँ — गले को relief मिलेगा।"
            "long"  -> "एक कप पानी में अदरक की 5 पतली slices डाल कर 5 मिनट simmer करें। छान कर एक चम्मच शहद और चुटकी भर हल्दी mix करें। दिन में 2 बार, 3–4 दिन तक लें।"
            else    -> "अदरक को पानी में 5 मिनट boil करें, छान कर शहद मिला लें। दिन में 2 बार लें।"
        }
    }
    val example = if (style.includeExamples && style.length != "short") when {
        useEng -> " For example: warm liquids loosen throat mucus, and honey coats the inflamed tissue."
        pure && mr -> " उदाहरणार्थ: कोमट द्रव घशातील कफ सैल करतात आणि मध सूज कमी करतो."
        pure -> " उदाहरण के लिए: गर्म तरल पदार्थ गले की बलगम ढीली करते हैं, और शहद सूजन वाली परत को राहत देता है।"
        mr -> " उदाहरणार्थ: गरम liquids घशातील कफ loosen करतात आणि मध tissue ला आराम देतो."
        else -> " उदाहरण के लिए: गरम liquids गले की बलगम loosen करते हैं और शहद tissue को coat करता है।"
    } else ""
    val disclaimer = if (style.showDisclaimers && style.length != "short") when {
        useEng -> " If symptoms last more than 3 days, please see a doctor."
        mr     -> " त्रास 3 दिवसांपेक्षा जास्त राहिल्यास, कृपया डॉक्टरांना दाखवा."
        else   -> " अगर तकलीफ़ 3 दिन से ज़्यादा रहे, तो कृपया डॉक्टर को दिखाएँ।"
    } else ""
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

/**
 * Sampling-temperature control for normal chat. Presets cover the common
 * intents; the slider gives exact control. While [isAuto] the slider sits on
 * the model's recommended value (shown as "Auto") so it reflects what's in
 * effect before any override.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CreativityCard(
    d: com.saarthi.core.i18n.SettingsDetailStrings,
    temperature: Float,
    isAuto: Boolean,
    onPreset: (Float) -> Unit,
    onSlide: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val presets = listOf(
        Triple(d.optPrecise, 0.3f, d.hintPrecise),
        Triple(d.optBalanced, 0.7f, d.hintBalanced),
        Triple(d.optCreative, 1.0f, d.hintCreative),
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                d.rsCreativity,
                style = MaterialTheme.typography.titleSmall.copy(color = SaarthiColors.Text),
                modifier = Modifier.weight(1f),
            )
            if (!isAuto) {
                Text(
                    d.rsReset,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = SaarthiColors.Marigold,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReset)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { (label, value, hint) ->
                // Highlight the preset the current temperature sits on (±0.05),
                // even in Auto mode if the model default happens to match.
                val selected = kotlin.math.abs(temperature - value) < 0.05f
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
                        .clickable { onPreset(value) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = if (selected) SaarthiColors.Marigold else SaarthiColors.Text2,
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                color = SaarthiColors.Text3,
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                d.rsTemperature,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text2),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (isAuto) "${d.rsAuto} · %.2f".format(temperature) else "%.2f".format(temperature),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (isAuto) SaarthiColors.Text3 else SaarthiColors.Marigold,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        androidx.compose.material3.Slider(
            value = temperature,
            onValueChange = { onSlide((it * 100).toInt() / 100f) },
            valueRange = 0f..1.5f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = SaarthiColors.Marigold,
                activeTrackColor = SaarthiColors.Marigold,
                inactiveTrackColor = SaarthiColors.Border,
            ),
            // Round knob instead of Material 3's default vertical-bar thumb.
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.Marigold)
                        .border(2.dp, SaarthiColors.Surface, CircleShape),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        Text(
            d.rsCreativityHelp,
            style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3, lineHeight = 14.sp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

// ── Manage downloads ──────────────────────────────────────────────────────────

@Composable
fun ManageDownloadsScreen(
    onBack: () -> Unit,
    onAddModel: () -> Unit = {},
    currentLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    viewModel: com.saarthi.app.ManageDownloadsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val d = currentLanguage.settingsDetail
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
            title = d.mdTitle,
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
                    d.mdPhoneStorage,
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
                    StorageLegend(color = SaarthiColors.Marigold, label = "${d.mdSaarthiModels} · %.1f GB".format(saarthiGb))
                    StorageLegend(color = Color(0x2EF5EEE3), label = d.mdOther)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "%.1f GB free of %.0f GB total".format(freeGb, totalGb),
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text2),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                d.mdInstalledModels,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text3,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            if (state.installed.isEmpty()) {
                Text(
                    d.mdNoModels,
                    style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text3),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.installed.forEach { m ->
                        InstalledModelRow(
                            model = m,
                            activeLabel = d.mdActive,
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
                    d.mdDownloadMore,
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
    activeLabel: String,
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
                    SaarthiChip(text = activeLabel, tone = ChipTone.Jade, small = true)
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
