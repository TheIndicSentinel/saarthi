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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.ui.components.ChipTone
import com.saarthi.core.ui.components.RangoliDivider
import com.saarthi.core.ui.components.SaarthiChip
import com.saarthi.core.ui.theme.DisplayAccent
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.SaarthiDisplayFont

@Composable
fun HomeScreen(
    onNavigate: (Route) -> Unit,
    onChangeModel: () -> Unit = {},
    onChangeLanguage: (SupportedLanguage) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    currentLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    greeting: String = currentLanguage.greeting,
    exploreSubtitle: String = currentLanguage.exploreSubtitle,
) {
    var showLanguagePicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        // Ambient marigold glow at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.18f), Color.Transparent),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            HomeTopBar(
                onLang = { showLanguagePicker = true },
                onMenu = onOpenSettings,
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(6.dp))
                GreetingBlock(currentLanguage)
                Spacer(Modifier.height(22.dp))
                HeroComposer(onClick = { onNavigate(Route.Assistant) })
                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "SPECIALIST MODES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SaarthiColors.Text3,
                            letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    SaarthiChip(text = "Coming soon", tone = ChipTone.Indigo, small = true)
                }
                Spacer(Modifier.height(10.dp))
                SpecialistsGrid(onClick = { onNavigate(Route.KisanSaathi) })
                Spacer(Modifier.height(18.dp))

                ThoughtOfTheDay()
                Spacer(Modifier.height(32.dp))
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
}

@Composable
private fun HomeTopBar(onLang: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // EN pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x0DF5EEE3))
                .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
                .clickable(onClick = onLang)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Public, null, tint = SaarthiColors.Text2, modifier = Modifier.size(14.dp))
            Text(
                "EN",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = SaarthiColors.Text2,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        // Active model pill (jade)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SaarthiColors.JadeSoft)
                .border(1.dp, SaarthiColors.JadeBd, RoundedCornerShape(999.dp))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SaarthiColors.Jade))
            Text(
                "Gemma · Active",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = SaarthiColors.Jade,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        // Menu icon
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x0DF5EEE3))
                .clickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MoreVert, "Settings", tint = SaarthiColors.Text, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun GreetingBlock(lang: SupportedLanguage) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.saarthi.core.ui.components.SaarthiLogo(size = 22.dp)
            Text(
                text = greetingHi(lang),
                style = DisplayAccent.copy(fontSize = 18.sp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${greetingEn()}, friend",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
                letterSpacing = (-0.6).sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "What can I help you with today?",
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SaarthiColors.Text3,
                fontSize = 14.sp,
            ),
        )
    }
}

private fun greetingEn(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun greetingHi(lang: SupportedLanguage): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 12 -> "सुप्रभात"
        h < 17 -> "नमस्ते"
        else -> "शुभ संध्या"
    }
}

@Composable
private fun HeroComposer(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.BorderHi, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        // marigold radial bleed top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SaarthiColors.MarigoldSoft, Color.Transparent),
                    ),
                ),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.MarigoldSoft)
                        .border(1.dp, SaarthiColors.MarigoldBd, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Ask Saarthi anything",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaarthiColors.Text,
                        ),
                    )
                    Text(
                        "Text · Voice · File · Image",
                        style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = SaarthiColors.Text3, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionPill("Summarize a PDF")
                SuggestionPill("PM Kisan eligibility")
            }
        }
    }
}

@Composable
private fun SuggestionPill(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x0AF5EEE3))
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(
                color = SaarthiColors.Text2,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun SpecialistsGrid(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpecialistTile(
                Icons.Outlined.Spa, "Kisan", "Farming · Mandi · Schemes",
                tone = ChipTone.Jade, onClick = onClick,
                modifier = Modifier.weight(1f),
            )
            SpecialistTile(
                Icons.AutoMirrored.Outlined.MenuBook, "Vidya", "NCERT · Science · GK",
                tone = ChipTone.Indigo, onClick = onClick,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpecialistTile(
                Icons.Outlined.Build, "Karigar", "Manuals · Error codes",
                tone = ChipTone.Terracotta, onClick = onClick,
                modifier = Modifier.weight(1f),
            )
            SpecialistTile(
                Icons.Outlined.Favorite, "Swasth", "Wellness · First-aid",
                tone = ChipTone.Marigold, onClick = onClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SpecialistTile(
    icon: ImageVector,
    name: String,
    sub: String,
    tone: ChipTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toneColor = when (tone) {
        ChipTone.Marigold -> SaarthiColors.Marigold
        ChipTone.Jade -> SaarthiColors.Jade
        ChipTone.Indigo -> SaarthiColors.Indigo
        ChipTone.Terracotta -> SaarthiColors.Terracotta
        else -> SaarthiColors.Marigold
    }
    val toneBg = when (tone) {
        ChipTone.Marigold -> SaarthiColors.MarigoldSoft
        ChipTone.Jade -> SaarthiColors.JadeSoft
        ChipTone.Indigo -> SaarthiColors.IndigoSoft
        ChipTone.Terracotta -> SaarthiColors.TerracottaSoft
        else -> SaarthiColors.MarigoldSoft
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        // soft radial bleed
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(110.dp)
                .background(
                    Brush.radialGradient(colors = listOf(toneBg, Color.Transparent)),
                ),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(toneBg)
                    .border(1.dp, toneColor.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = toneColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaarthiColors.Text,
                    ),
                )
                Text(
                    "Soon",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SaarthiColors.Text3,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.2.sp,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x0DF5EEE3))
                        .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
    }
}

@Composable
private fun ThoughtOfTheDay() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        SaarthiColors.Marigold.copy(alpha = 0.08f),
                        SaarthiColors.Terracotta.copy(alpha = 0.05f),
                    ),
                ),
            )
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Star, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(12.dp))
                Text(
                    "THOUGHT OF THE DAY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SaarthiColors.Marigold,
                        letterSpacing = 1.4.sp,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "विद्या ददाति विनयम्",
                style = DisplayAccent.copy(fontSize = 18.sp, color = SaarthiColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "\"Knowledge gives humility\"",
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
    }
}

// ── Language picker dialog ───────────────────────────────────────────────────

@Composable
private fun LanguagePickerDialog(
    currentLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaarthiColors.Bg2,
        title = {
            Text(
                "Change language",
                style = MaterialTheme.typography.titleLarge,
                color = SaarthiColors.Text,
            )
        },
        text = {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(SupportedLanguage.entries) { lang ->
                    val isSelected = lang == currentLanguage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) SaarthiColors.MarigoldSoft
                                else SaarthiColors.Surface
                            )
                            .border(
                                1.dp,
                                if (isSelected) SaarthiColors.MarigoldBd else SaarthiColors.Border,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) SaarthiColors.MarigoldBd
                                    else Color(0x0DF5EEE3)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                lang.nativeName.take(1),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = SaarthiDisplayFont,
                                    color = if (isSelected) SaarthiColors.Marigold else SaarthiColors.Text2,
                                ),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lang.nativeName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = if (isSelected) SaarthiColors.Marigold else SaarthiColors.Text,
                                ),
                            )
                            Text(
                                lang.englishName,
                                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                            )
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SaarthiColors.Text2)
            }
        },
    )
}

// ── Placeholder destinations (Kisan / Knowledge / Field) ─────────────────────

@Composable
fun KisanSaathiPlaceholder(onBack: () -> Unit) = PackPlaceholder("Kisan Saathi", "Coming soon — farming guidance, mandi prices, govt schemes.", SaarthiColors.Jade, onBack)
@Composable fun KnowledgePlaceholder(onBack: () -> Unit) = PackPlaceholder("Knowledge", "Coming soon — NCERT, science, general knowledge.", SaarthiColors.Indigo, onBack)
@Composable fun FieldExpertPlaceholder(onBack: () -> Unit) = PackPlaceholder("Field Expert", "Coming soon — technical manuals, error codes.", SaarthiColors.Terracotta, onBack)

@Composable
private fun PackPlaceholder(name: String, sub: String, accent: Color, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg).statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(name, style = MaterialTheme.typography.displayLarge.copy(fontSize = 28.sp, color = accent, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2))
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) {
            Text("← Back", color = SaarthiColors.Marigold)
        }
    }
}
