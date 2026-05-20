package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DarkMode
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
) {
    var notifOn by remember { mutableStateOf(true) }
    var darkOn by remember { mutableStateOf(true) }

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

            SectionLabel("App")
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                title = "Language",
                subtitle = "Change interface language",
                trailing = { ChevronRight() },
                onClick = { onNavigate("lang") },
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
                trailing = { SaarthiToggle(on = notifOn, onToggle = { notifOn = !notifOn }) },
            )
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.DarkMode, null) },
                title = "Dark theme",
                trailing = { SaarthiToggle(on = darkOn, onToggle = { darkOn = !darkOn }) },
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
fun ResponseStyleScreen(onBack: () -> Unit) {
    var length by remember { mutableStateOf("medium") }
    var tone by remember { mutableStateOf("balanced") }
    var styleMix by remember { mutableStateOf("mix") }
    var disclaim by remember { mutableStateOf(true) }
    var examples by remember { mutableStateOf(true) }

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
                value = length,
                onChange = { length = it },
                options = listOf("short" to "Short", "medium" to "Medium", "long" to "Long"),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = "Tone",
                value = tone,
                onChange = { tone = it },
                options = listOf("warm" to "Warm", "balanced" to "Balanced", "formal" to "Formal"),
            )
            Spacer(Modifier.height(14.dp))
            SegmentedCard(
                title = "Language style",
                value = styleMix,
                onChange = { styleMix = it },
                options = listOf("pure" to "Pure", "mix" to "Hinglish", "eng" to "English"),
            )

            Spacer(Modifier.height(20.dp))
            SaarthiListRow(
                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                title = "Show disclaimers",
                subtitle = "Add safety notes for legal & medical topics",
                trailing = { SaarthiToggle(on = disclaim, onToggle = { disclaim = !disclaim }) },
            )
            Spacer(Modifier.height(6.dp))
            SaarthiListRow(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                title = "Include examples",
                subtitle = "Anchor explanations with real-world cases",
                trailing = { SaarthiToggle(on = examples, onToggle = { examples = !examples }) },
            )
        }
    }
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
fun ManageDownloadsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Manage downloads", subtitle = "Models stored on this device", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Use the model picker to manage downloads. Settings → Active model lets you switch which model is loaded.",
                style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2),
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
