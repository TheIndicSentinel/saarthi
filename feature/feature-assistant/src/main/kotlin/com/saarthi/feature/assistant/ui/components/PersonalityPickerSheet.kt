package com.saarthi.feature.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.i18n.Personality
import com.saarthi.core.i18n.PersonalityAccent
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Bottom-sheet for picking a persona. Vertical list — every persona visible
 * via scroll, no horizontal hidden cards. Each row shows emoji avatar +
 * name + one-line tagline. Active row has a marigold-tinted background
 * and a check badge.
 *
 * On Compact (1B) tier, rows are dimmed + an inline banner explains why.
 */
@Composable
fun PersonalityPickerSheet(
    personalities: List<Personality>,
    selectedId: String,
    supportedForCurrentModel: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 16.dp),
    ) {
        Text(
            "Pick a persona",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (supportedForCurrentModel)
                "Switching starts a new chat. Existing conversations stay saved."
            else
                "The compact 1B model can't sustain a persona — switch to Gemma 3n or Gemma 4 to use this.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (supportedForCurrentModel) SaarthiColors.Text3 else SaarthiColors.Rose,
                fontSize = 12.sp,
            ),
        )
        Spacer(Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(personalities, key = { it.id }) { p ->
                PersonalityRow(
                    personality = p,
                    selected = p.id == selectedId,
                    enabled = supportedForCurrentModel,
                    onClick = {
                        if (supportedForCurrentModel) {
                            onPick(p.id)
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PersonalityRow(
    personality: Personality,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tone = accentColor(personality.accent)
    val toneSoftBg = accentSoftBg(personality.accent)
    val bg = when {
        !enabled -> SaarthiColors.Surface.copy(alpha = 0.5f)
        selected -> toneSoftBg
        else     -> SaarthiColors.Surface
    }
    val border = when {
        selected -> tone
        else     -> SaarthiColors.Border
    }
    val titleColor = if (enabled) SaarthiColors.Text else SaarthiColors.Text.copy(alpha = 0.5f)
    val taglineColor = if (enabled) SaarthiColors.Text3 else SaarthiColors.Text4

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Avatar — large emoji on a tone-soft tile.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(toneSoftBg)
                .border(1.dp, tone.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(personality.emoji, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                personality.displayName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                ),
            )
            Text(
                personality.tagline,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = taglineColor,
                    fontSize = 12.5.sp,
                ),
            )
        }

        if (selected && enabled) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(tone),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected persona",
                    tint = SaarthiColors.OnMarigold,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun accentColor(accent: PersonalityAccent): Color = when (accent) {
    PersonalityAccent.MARIGOLD   -> SaarthiColors.Marigold
    PersonalityAccent.JADE       -> SaarthiColors.Jade
    PersonalityAccent.INDIGO     -> SaarthiColors.Indigo
    PersonalityAccent.TERRACOTTA -> SaarthiColors.Terracotta
    PersonalityAccent.ROSE       -> SaarthiColors.Rose
}

@Composable
private fun accentSoftBg(accent: PersonalityAccent): Color = when (accent) {
    PersonalityAccent.MARIGOLD   -> SaarthiColors.MarigoldSoft
    PersonalityAccent.JADE       -> SaarthiColors.JadeSoft
    PersonalityAccent.INDIGO     -> SaarthiColors.IndigoSoft
    PersonalityAccent.TERRACOTTA -> SaarthiColors.TerracottaSoft
    PersonalityAccent.ROSE       -> SaarthiColors.RoseSoft
}
