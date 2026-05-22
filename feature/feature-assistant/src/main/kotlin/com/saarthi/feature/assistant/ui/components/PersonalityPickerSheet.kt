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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
 * Bottom-sheet content for picking a Personality Pal. Caller wraps this in a
 * ModalBottomSheet. Renders a horizontal carousel of cards keyed by accent;
 * the active personality has a checkmark + tinted ring.
 *
 * If [supportedForCurrentModel] is false (Compact / 1B tier), the carousel
 * is shown with a disabled scrim + an explanatory line so the user knows the
 * feature exists but doesn't work on this model.
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
            .padding(bottom = 24.dp, top = 4.dp),
    ) {
        Text(
            "Choose a personality",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (supportedForCurrentModel)
                "Switching starts a new chat. Your existing conversations stay saved."
            else
                "The compact model can't sustain a persona. Switch to Gemma 3n or Gemma 4 to use this feature.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (supportedForCurrentModel) SaarthiColors.Text3 else SaarthiColors.Rose,
                fontSize = 12.sp,
            ),
        )
        Spacer(Modifier.height(16.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(personalities, key = { it.id }) { p ->
                PersonalityCard(
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
private fun PersonalityCard(
    personality: Personality,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tone = accentColor(personality.accent)
    val toneBg = accentSoftBg(personality.accent)
    val borderColor = when {
        !enabled -> SaarthiColors.Border
        selected -> tone
        else -> SaarthiColors.Border
    }
    val cardAlpha = if (enabled) 1f else 0.45f

    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SaarthiColors.Surface.copy(alpha = if (enabled) 1f else 0.5f))
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(toneBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    personality.emoji,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (selected && enabled) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(tone),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = SaarthiColors.OnMarigold,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Text(
            personality.displayName,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) SaarthiColors.Text else SaarthiColors.Text.copy(alpha = cardAlpha),
            ),
        )
        Text(
            personality.tagline,
            style = MaterialTheme.typography.bodySmall.copy(
                color = SaarthiColors.Text3.copy(alpha = cardAlpha),
                fontSize = 11.5.sp,
            ),
        )
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
