package com.saarthi.feature.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun ModelStatusChip(
    isStreaming: Boolean,
    tokensPerSecond: Float,
    modelReady: Boolean,
    activeModelName: String?,
    modifier: Modifier = Modifier,
) {
    val dotColor by animateColorAsState(
        targetValue = when {
            !modelReady -> SaarthiColors.Error
            isStreaming -> SaarthiColors.CyberTeal
            else -> SaarthiColors.Success
        },
        label = "dot_color",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse_alpha",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SaarthiColors.GlassSurface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (isStreaming) alpha else 1f))
        )
        Text(
            text = when {
                !modelReady -> "Model not loaded"
                isStreaming && tokensPerSecond > 0 -> "${"%.0f".format(tokensPerSecond)} tok/s"
                isStreaming -> "Generating…"
                activeModelName != null -> "$activeModelName · Ready"
                else -> "Gemma · Ready"
            },
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextMuted,
        )
    }
}
