package com.saarthi.feature.assistant.ui.pack

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.data.KisanPackInstaller
import com.saarthi.feature.assistant.viewmodel.KisanPackViewModel

/**
 * Landing screen for the Kisan knowledge pack.
 *
 * This is the differentiator between "a chat persona with extra
 * context" (which is what we shipped before) and "an actual pack
 * the user can browse, see the sources of, and decide what to ask".
 * It surfaces the curated content so the value of the pack is
 * visible before the user even sends a message:
 *
 *   • Status card — pack version, source attribution, "100% offline"
 *     badge, model-capability warning when the user's on Gemma 1B.
 *   • Suggested questions — chips derived from the pack's topics,
 *     tappable to jump straight into chat (chat-side prefill arrives
 *     in the next iteration; v1 just opens chat in Kisan mode).
 *   • Open chat CTA — explicit big button to start a Kisan-mode chat.
 *   • Topic list — every pack entry as a tappable card, expandable
 *     to show full content + a "View source" link to the original
 *     Govt portal.
 */
@Composable
fun KisanPackScreen(
    onBack: () -> Unit,
    onOpenKisanChat: () -> Unit,
    viewModel: KisanPackViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val userState by viewModel.userState.collectAsStateWithLifecycle()
    var showStatePicker by remember { mutableStateOf(false) }

    if (showStatePicker) {
        StatePickerSheet(
            current = userState,
            onSelect = viewModel::setUserState,
            onDismiss = { showStatePicker = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.Bg)
            .statusBarsPadding(),
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = SaarthiColors.Text2,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "🌾  Kisan Saathi",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = SaarthiColors.Text,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.weight(1f),
            )
            StateChip(state = userState, onClick = { showStatePicker = true })
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refresh pack content",
                    tint = SaarthiColors.Text3,
                )
            }
        }

        when {
            ui.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = SaarthiColors.Marigold)
                }
            }
            ui.pack == null -> {
                EmptyPackState(modifier = Modifier.padding(24.dp))
            }
            else -> {
                LoadedPackContent(
                    pack = ui.pack!!,
                    packSupportedOnCurrentModel = ui.packSupportedOnCurrentModel,
                    activeModelName = ui.activeModelName,
                    language = language,
                    onOpenChat = onOpenKisanChat,
                )
            }
        }
    }
}

// ── Loaded state ───────────────────────────────────────────────────

@Composable
private fun LoadedPackContent(
    pack: KisanPackInstaller.InstalledPack,
    packSupportedOnCurrentModel: Boolean,
    activeModelName: String?,
    language: com.saarthi.core.i18n.SupportedLanguage,
    onOpenChat: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // Suggested-question chips derived from pack topic headlines. Cap
    // at 6 so the section stays scannable.
    val quickAsks = remember(pack) {
        pack.entries.take(6).map { entry -> entry.topic.substringBefore(" —") }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Status card ──
        item {
            StatusCard(
                pack = pack,
                packSupportedOnCurrentModel = packSupportedOnCurrentModel,
                activeModelName = activeModelName,
                offlineBadge = language.kisanOfflineBadge,
            )
        }

        // ── Capability warning ──
        if (!packSupportedOnCurrentModel) {
            item {
                CapabilityHint(activeModelName)
            }
        }

        // ── Quick-ask section header ──
        item {
            SectionHeader(text = language.kisanQuickAsk, subtitle = "")
        }

        // ── Suggested questions (chips) ──
        item {
            QuickAskGrid(
                quickAsks = quickAsks,
                onAsk = { _ -> onOpenChat() },
            )
        }

        // ── Open-chat CTA ──
        item {
            OpenChatCta(label = language.kisanOpenChat, onClick = onOpenChat)
        }

        // ── Topic list section header ──
        item {
            SectionHeader(
                text = language.kisanTopicsHeader,
                subtitle = "${pack.entries.size} · ${language.kisanOfflineBadge}",
            )
        }

        // ── Topic cards ──
        items(items = pack.entries, key = { it.topic + it.content.hashCode() }) { entry ->
            TopicCard(entry = entry, onOpenSource = { url -> uriHandler.openUri(url) })
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Sub-composables ────────────────────────────────────────────────

@Composable
private fun StatusCard(
    pack: KisanPackInstaller.InstalledPack,
    packSupportedOnCurrentModel: Boolean,
    activeModelName: String?,
    offlineBadge: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        SaarthiColors.Jade.copy(alpha = 0.14f),
                        SaarthiColors.Marigold.copy(alpha = 0.06f),
                    ),
                ),
            )
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Spa,
                    contentDescription = null,
                    tint = SaarthiColors.Jade,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pack.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = SaarthiColors.Text,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Pill(text = "v${pack.version}", tone = SaarthiColors.Jade)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = pack.source.ifBlank { "Curated by Saarthi from Government of India open data." },
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2, lineHeight = 19.sp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgeChip(icon = Icons.Outlined.WifiOff, label = offlineBadge)
                BadgeChip(icon = Icons.Outlined.AutoAwesome, label = "${pack.entries.size} topics")
                if (pack.language.isNotBlank()) BadgeChip(label = pack.language.uppercase())
            }
        }
    }
}

@Composable
private fun CapabilityHint(activeModelName: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SaarthiColors.Marigold.copy(alpha = 0.10f))
            .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column {
            Text(
                text = "Best with Gemma 4 or 3n",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = SaarthiColors.Marigold,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "You're on ${activeModelName?.substringAfter('/')?.substringBefore('.') ?: "the small model"}. The Kisan pack is browseable on every model, but chat answers stay sharper on the larger ones — switch from Settings → Models.",
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text2, lineHeight = 17.sp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = SaarthiColors.Text3,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
    }
}

@Composable
private fun QuickAskGrid(quickAsks: List<String>, onAsk: (String) -> Unit) {
    // Two-column flow of chips. We keep the chip layout simple (Row of
    // Rows) instead of LazyVerticalGrid because this lives inside a
    // LazyColumn already.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quickAsks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ask ->
                    QuickAskChip(
                        text = ask,
                        onClick = { onAsk(ask) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Single-item last row keeps the grid edges aligned.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickAskChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = SaarthiColors.Text,
                lineHeight = 19.sp,
            ),
            maxLines = 3,
        )
    }
}

@Composable
private fun OpenChatCta(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(SaarthiColors.Marigold, SaarthiColors.MarigoldDim),
                ),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                color = SaarthiColors.Bg,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopicCard(
    entry: KisanPackInstaller.InstalledEntry,
    onOpenSource: (String) -> Unit,
) {
    var expanded by rememberSaveable(entry.topic) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = entry.topic,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = SaarthiColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = SaarthiColors.Text3,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // FlowRow so long multi-word tags wrap onto the next line
                // instead of squeezing into one Row (which made pills clip and
                // their text wrap, leaving tall, uneven cards).
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entry.tags.take(4).forEach { tag ->
                        BadgeChip(label = "#$tag")
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = SaarthiColors.Text2,
                        lineHeight = 20.sp,
                    ),
                )
                entry.sourceUrl?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenSource(url) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = SaarthiColors.Marigold,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "View source",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SaarthiColors.Marigold,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, tone: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.16f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = tone,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun BadgeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = SaarthiColors.Text3, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text2),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyPackState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "🌾",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Kisan pack not installed yet",
            style = MaterialTheme.typography.titleMedium.copy(color = SaarthiColors.Text),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The bundled starter pack should install on first launch. Try closing and reopening Saarthi, or pull a fresh build.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = SaarthiColors.Text2,
                lineHeight = 19.sp,
            ),
        )
    }
}
