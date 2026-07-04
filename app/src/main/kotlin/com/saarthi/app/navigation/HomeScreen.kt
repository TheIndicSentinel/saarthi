package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    /**
     * Kisan tile does more than navigate — it also pre-selects the
     * Kisan persona so the curated farming pack auto-merges into RAG
     * the moment the chat opens. Handled in [SaarthiNavHost] where the
     * persona ViewModel is in scope.
     */
    onKisanTap: () -> Unit = { onNavigate(Route.KisanSaathi) },
    /** Called when a home-screen suggestion chip is tapped. Opens the chat with the chip text pre-filled. */
    onSuggestionChip: (String) -> Unit = { onNavigate(Route.Assistant) },
    currentLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    greeting: String = currentLanguage.greeting,
    exploreSubtitle: String = currentLanguage.exploreSubtitle,
    /** The user's name, learned from chat (USER_SCOPE memory). When present the
     *  greeting personalises to "Good evening, {name}"; otherwise it's generic. */
    userName: String? = null,
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
                language = currentLanguage,
                onLang = { showLanguagePicker = true },
                onMenu = onOpenSettings,
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(6.dp))
                GreetingBlock(currentLanguage, userName)
                Spacer(Modifier.height(22.dp))
                HeroComposer(
                    lang = currentLanguage,
                    onClick = { onNavigate(Route.Assistant) },
                    onSuggestionChip = onSuggestionChip,
                )
                Spacer(Modifier.height(20.dp))

                Text(
                    currentLanguage.specialistModesLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SaarthiColors.Text3,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                // Per-tile "Live" / "Soon" badges now communicate status,
                // so the section-level chip is gone (it was redundant).
                Spacer(Modifier.height(10.dp))
                SpecialistsGrid(
                    lang           = currentLanguage,
                    onKisanClick   = onKisanTap,
                    onVidyaClick   = { onNavigate(Route.Vidya) },
                    onKarigarClick = { onNavigate(Route.Karigar) },
                    onSwasthClick  = { onNavigate(Route.Swasth) },
                )
                Spacer(Modifier.height(18.dp))

                ThoughtOfTheDay(currentLanguage)
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
private fun HomeTopBar(
    language: SupportedLanguage,
    onLang: () -> Unit,
    onMenu: () -> Unit,
) {
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
                language.code.uppercase(),
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
private fun GreetingBlock(lang: SupportedLanguage, userName: String?) {
    // Compute hour on every recomposition so the greeting updates correctly when
    // the user returns to this screen later in the day (remember(lang) would have
    // cached "Good morning" from the morning and never refreshed it).
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = lang.timeGreeting(h)
    Column {
        Text(
            buildAnnotatedString {
                // Personalise with the user's name when we know it ("Good
                // evening, Arjun"); otherwise show just the time-of-day greeting.
                if (!userName.isNullOrBlank()) {
                    append("$greeting, ")
                    withStyle(SpanStyle(color = SaarthiColors.Marigold)) { append(userName) }
                } else {
                    append(greeting)
                }
            },
            // SAME 30sp in every language — never auto-shrink (a smaller Hindi
            // greeting than English reads as broken).
            // NO maxLines / NO ellipsis: Compose mis-measures Devanagari
            // conjunct/matra widths, so with maxLines=1 even a short
            // "शुभ संध्या" / "शुभ संध्याकाळ" phantom-overflowed and rendered
            // as "शुभ…" (field reports, Hindi + Marathi). The greeting must
            // ALWAYS show complete text — in the rare genuinely-too-long case
            // (very long name) it wraps, which is the original, accepted
            // behaviour. Never re-add truncation here.
            // NO negative letterSpacing either — same Devanagari measurement
            // problem; Latin is visually unaffected by dropping it.
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            lang.homeHelperText,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SaarthiColors.Text3,
                fontSize = 14.sp,
            ),
        )
    }
}

@Composable
private fun HeroComposer(lang: SupportedLanguage, onClick: () -> Unit, onSuggestionChip: (String) -> Unit = {}) {
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
                        lang.askCardTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaarthiColors.Text,
                        ),
                    )
                    Text(
                        lang.homeInputModes,
                        style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = SaarthiColors.Text3, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(14.dp))
            // Three highest-frequency quick actions, localized. The chip text is
            // also the prompt sent on tap, so it stays a full natural phrase.
            val chips = lang.homeQuickActions
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.getOrNull(0)?.let { SuggestionPill(it, onClick = { onSuggestionChip(it) }) }
                chips.getOrNull(1)?.let { SuggestionPill(it, onClick = { onSuggestionChip(it) }) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.getOrNull(2)?.let { SuggestionPill(it, onClick = { onSuggestionChip(it) }) }
            }
        }
    }
}

@Composable
private fun SuggestionPill(text: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x0AF5EEE3))
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
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
private fun SpecialistsGrid(
    lang: SupportedLanguage,
    onKisanClick: () -> Unit,
    onVidyaClick: () -> Unit,
    onKarigarClick: () -> Unit,
    onSwasthClick: () -> Unit,
) {
    // height(IntrinsicSize.Min) + fillMaxHeight() makes both tiles in a row
    // match the taller one's height, so tiles never stretch unevenly when one
    // label/subtitle wraps to two lines — consistent across screen sizes.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpecialistTile(
                Icons.Outlined.Spa, "Kisan", lang.kisanKeywords,
                tone = ChipTone.Jade, onClick = onKisanClick,
                liveLabel = lang.liveBadge, soonLabel = lang.soonBadge,
                comingSoon = false,   // Kisan is LIVE
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SpecialistTile(
                Icons.AutoMirrored.Outlined.MenuBook, "Vidya", lang.vidyaKeywords,
                tone = ChipTone.Indigo, onClick = onVidyaClick,
                liveLabel = lang.liveBadge, soonLabel = lang.soonBadge,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpecialistTile(
                Icons.Outlined.Build, "Karigar", lang.karigarKeywords,
                tone = ChipTone.Terracotta, onClick = onKarigarClick,
                liveLabel = lang.liveBadge, soonLabel = lang.soonBadge,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SpecialistTile(
                Icons.Outlined.Favorite, "Swasth", lang.swasthKeywords,
                tone = ChipTone.Marigold, onClick = onSwasthClick,
                liveLabel = lang.liveBadge, soonLabel = lang.soonBadge,
                modifier = Modifier.weight(1f).fillMaxHeight(),
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
    liveLabel: String,
    soonLabel: String,
    modifier: Modifier = Modifier,
    comingSoon: Boolean = true,
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
            // Top row: icon on the left, status badge on the right (matching design).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Spacer(Modifier.weight(1f))
                if (comingSoon) {
                    Text(
                        soonLabel,
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
                } else {
                    Text(
                        liveLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = toneColor,
                            fontSize = 9.5.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(toneBg)
                            .border(1.dp, toneColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
    }
}

@Composable
private fun ThoughtOfTheDay(lang: SupportedLanguage) {
    // Cycles deterministically by day-of-year — same wisdom everywhere on
    // the same calendar day, no per-device drift, no random feel. The
    // catalog lives in core-i18n so the daily notification can pull the
    // exact same entry without duplicating the list.
    val wisdom = remember { com.saarthi.core.i18n.DailyWisdomCatalog.forDate() }
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
                    lang.thoughtOfDayLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SaarthiColors.Marigold,
                        letterSpacing = 1.4.sp,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Localized quote in the user's language (no Sanskrit-only content).
            Text(
                wisdom.localized(lang),
                style = DisplayAccent.copy(fontSize = 18.sp, color = SaarthiColors.Text),
            )
            // For non-English users, show the English gloss as a quiet secondary
            // line (helps if the script differs); English users see just the quote.
            if (lang != SupportedLanguage.ENGLISH) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "\"${wisdom.english}\"",
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                )
            }
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
                Text(currentLanguage.cancelLabel, color = SaarthiColors.Text2)
            }
        },
    )
}

// ── Placeholder destinations (Kisan / Knowledge / Field) ─────────────────────

// ── Pack landing placeholders ─────────────────────────────────────────
// Kisan is LIVE — its tile opens AssistantScreen with the Kisan persona
// pre-selected (see SaarthiNavHost.onKisanTap). The legacy
// `KisanSaathiPlaceholder` route is kept as a defensive fallback for
// any deep-link / cached intent that still points there.
@Composable
fun KisanSaathiPlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Kisan Saathi",
    sub = "Tap the Kisan tile on Home to start a farming chat — the curated pack auto-loads.",
    accent = SaarthiColors.Jade,
    onBack = onBack,
)
@Composable
fun KnowledgePlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Knowledge",
    sub = "Coming soon — NCERT, science, and general knowledge curated by subject.",
    accent = SaarthiColors.Indigo,
    onBack = onBack,
)
@Composable
fun FieldExpertPlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Field Expert",
    sub = "Coming soon — technical manuals, error codes, repair walkthroughs.",
    accent = SaarthiColors.Terracotta,
    onBack = onBack,
)
@Composable
fun VidyaPlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Vidya",
    sub = "Coming soon — NCERT-aligned study companion for students.",
    accent = SaarthiColors.Indigo,
    onBack = onBack,
)
@Composable
fun KarigarPlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Karigar",
    sub = "Coming soon — technical manuals, error codes, and field-repair walkthroughs.",
    accent = SaarthiColors.Terracotta,
    onBack = onBack,
)
@Composable
fun SwasthPlaceholder(onBack: () -> Unit) = PackPlaceholder(
    name = "Swasth",
    sub = "Coming soon — wellness tips and first-aid guidance, offline.",
    accent = SaarthiColors.Marigold,
    onBack = onBack,
)

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
