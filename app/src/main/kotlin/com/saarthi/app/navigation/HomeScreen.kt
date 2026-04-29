package com.saarthi.app.navigation

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.ui.components.GlassmorphicCard
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.saarthiColors

@Composable
fun HomeScreen(
    onNavigate: (Route) -> Unit,
    onChangeModel: () -> Unit = {},
    onChangeLanguage: (SupportedLanguage) -> Unit = {},
    currentLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    greeting: String = currentLanguage.greeting,
    exploreSubtitle: String = currentLanguage.exploreSubtitle,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    color = SaarthiColors.Gold,
                )
                Text(
                    exploreSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SaarthiColors.TextSecondary,
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Settings",
                        tint = SaarthiColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(currentLanguage.changeLanguage, color = SaarthiColors.TextPrimary) },
                        onClick = { showMenu = false; showLanguagePicker = true },
                    )
                    DropdownMenuItem(
                        text = { Text(currentLanguage.changeModel, color = SaarthiColors.TextPrimary) },
                        onClick = { showMenu = false; onChangeModel() },
                    )
                }
            }
        }

        // Pack items
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PackItem(
                emoji = "💬",
                title = currentLanguage.appName,
                subtitle = when (currentLanguage) {
                    SupportedLanguage.ENGLISH -> "Personal AI — ask anything, attach files, voice input"
                    SupportedLanguage.HINDI   -> "अपना AI — कुछ भी पूछें, फ़ाइल जोड़ें, आवाज़ से बोलें"
                    else -> "Personal AI — ask anything, attach files, voice input"
                },
                accentColor = SaarthiColors.Gold,
                onClick = { onNavigate(Route.Assistant) },
            )
            PackItem(
                emoji = "💰",
                title = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "मनी मेंटर"
                    else -> "Money Mentor"
                },
                subtitle = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "खर्च ट्रैक करें · UPI · बचत योजनाएं"
                    else -> "Track spending · UPI · Savings plans"
                },
                accentColor = MaterialTheme.saarthiColors.moneyGreen,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.MoneyMentor) },
            )
            PackItem(
                emoji = "🌾",
                title = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "किसान साथी"
                    else -> "Kisan Saathi"
                },
                subtitle = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "खेती सलाह · मंडी भाव · सरकारी योजनाएं"
                    else -> "Farming guidance · Mandi prices · Govt schemes"
                },
                accentColor = MaterialTheme.saarthiColors.kisanEarth,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.KisanSaathi) },
            )
            PackItem(
                emoji = "📚",
                title = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "ज्ञान केंद्र"
                    else -> "Knowledge Pack"
                },
                subtitle = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "NCERT · विज्ञान · सामान्य ज्ञान"
                    else -> "NCERT · Science · General knowledge"
                },
                accentColor = MaterialTheme.saarthiColors.knowledgePurple,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.Knowledge) },
            )
            PackItem(
                emoji = "🔧",
                title = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "फील्ड एक्सपर्ट"
                    else -> "Field Expert"
                },
                subtitle = when (currentLanguage) {
                    SupportedLanguage.HINDI -> "तकनीकी गाइड · एरर कोड · मैनुअल"
                    else -> "Technical manuals · Error codes · Guides"
                },
                accentColor = MaterialTheme.saarthiColors.fieldBlue,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.FieldExpert) },
            )
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentLanguage = currentLanguage,
            onLanguageSelected = {
                showLanguagePicker = false
                onChangeLanguage(it)
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaarthiColors.NavyMid,
        title = {
            Text(
                currentLanguage.changeLanguage,
                color = SaarthiColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(SupportedLanguage.entries) { lang ->
                    val isSelected = lang == currentLanguage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) SaarthiColors.Gold.copy(alpha = 0.1f)
                                else SaarthiColors.GlassSurface.copy(alpha = 0.3f)
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) SaarthiColors.Gold.copy(0.4f) else androidx.compose.ui.graphics.Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Removed flag icon for a cleaner, text-pure interface.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(SaarthiColors.NavyDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                lang.nativeName.take(1),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextMuted
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lang.nativeName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextPrimary,
                            )
                            Text(
                                lang.englishName,
                                style = MaterialTheme.typography.labelSmall,
                                color = SaarthiColors.TextMuted,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = SaarthiColors.Gold,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SaarthiColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun PackItem(
    emoji: String,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    badge: String? = null,
    onClick: () -> Unit,
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accentColor,
        onClick = onClick,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = MaterialTheme.typography.headlineMedium)
                if (badge != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = SaarthiColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = SaarthiColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SaarthiColors.TextMuted)
        }
    }
}

@Composable fun MoneyMentorPlaceholder(onBack: () -> Unit) = PackPlaceholder("💰", "Money Mentor", SaarthiColors.MoneyGreen, onBack)
@Composable fun KisanSaathiPlaceholder(onBack: () -> Unit) = PackPlaceholder("🌾", "Kisan Saathi", SaarthiColors.KisanEarth, onBack)
@Composable fun KnowledgePlaceholder(onBack: () -> Unit) = PackPlaceholder("📚", "Knowledge Pack", SaarthiColors.KnowledgePurple, onBack)
@Composable fun FieldExpertPlaceholder(onBack: () -> Unit) = PackPlaceholder("🔧", "Field Expert", SaarthiColors.FieldBlue, onBack)

@Composable
private fun PackPlaceholder(
    emoji: String,
    name: String,
    accent: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
            .statusBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "Coming soon — this feature is in development.",
            style = MaterialTheme.typography.bodyLarge,
            color = SaarthiColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.TextButton(onClick = onBack) {
            Text("← Back", color = SaarthiColors.Gold)
        }
    }
}
