package com.saarthi.feature.assistant.ui.components

import com.saarthi.core.i18n.SupportedLanguage
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.DisplayAccent
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Full-screen voice listening overlay matching SAARTHI_HANDOFF.md §B.6.
 * - Three staggered pulse rings around a marigold mic disc
 * - Live transcribed text in the top half
 * - Close / Send / Stop controls at the bottom
 */
@Composable
fun VoiceModeOverlay(
    transcribedText: String,
    isListening: Boolean,
    language: SupportedLanguage,
    onClose: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "voice")
    val pulse1 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1",
    )
    val pulse2 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, delayMillis = 300, easing = LinearEasing), RepeatMode.Restart),
        label = "ring2",
    )
    val pulse3 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, delayMillis = 600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring3",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Theme background, not a hardcoded near-black — otherwise in light
            // mode the dark overlay clashed with the theme-coloured (dark) text,
            // making the voice screen unreadable.
            .background(SaarthiColors.Bg)
            .clickable(enabled = false) {} // swallow taps to background
    ) {
        // ambient marigold glow
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SaarthiColors.Marigold.copy(alpha = 0.30f), Color.Transparent),
                    ),
                ),
        )

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 28.dp),
        ) {
            // Top bar — close + listening indicator
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.Surface)
                        .border(1.dp, SaarthiColors.Border, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = SaarthiColors.Text, modifier = Modifier.size(20.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isListening) SaarthiColors.Rose else SaarthiColors.Jade),
                    )
                    Text(
                        if (isListening) language.voiceBadgeListening else language.voiceBadgeCaptured,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SaarthiColors.Text2,
                            letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                Box(Modifier.size(40.dp))
            }

            Spacer(Modifier.height(28.dp))

            // Transcribed text
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (transcribedText.isBlank() && isListening) language.voiceStatusListening
                    else language.voiceStatusHeard,
                    style = MaterialTheme.typography.labelMedium.copy(color = SaarthiColors.Text3),
                )
                Spacer(Modifier.height(10.dp))
                if (transcribedText.isNotBlank()) {
                    Text(
                        "“$transcribedText”",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SaarthiColors.Text,
                        ),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        language.voicePrompt,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = SaarthiColors.Text3,
                            fontSize = 14.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    language.voiceLangHint,
                    style = DisplayAccent.copy(fontSize = 14.sp, color = SaarthiColors.Marigold.copy(alpha = 0.7f)),
                )
            }

            Spacer(Modifier.weight(1f))

            // Pulse rings + mic
            Box(modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                if (isListening) {
                    PulseRing(progress = pulse1)
                    PulseRing(progress = pulse2)
                    PulseRing(progress = pulse3)
                }
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(if (isListening) SaarthiColors.Marigold else SaarthiColors.Surface)
                        .border(
                            2.dp,
                            if (isListening) SaarthiColors.MarigoldBd else SaarthiColors.BorderHi,
                            CircleShape,
                        )
                        .clickable(enabled = !isListening, onClick = onRestart),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        "Mic",
                        tint = if (isListening) SaarthiColors.OnMarigold else SaarthiColors.Marigold,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom row — Close · Send · Stop
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleAction(
                    onClick = onClose,
                    icon = Icons.Default.Close,
                    bg = SaarthiColors.Surface,
                    tint = SaarthiColors.Text2,
                    contentDescription = "Cancel",
                )
                // Send (primary marigold pill)
                Row(
                    modifier = Modifier
                        .height(54.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SaarthiColors.Marigold)
                        .clickable(onClick = onSend)
                        .padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = SaarthiColors.OnMarigold, modifier = Modifier.size(16.dp))
                    Text(
                        "Send",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaarthiColors.OnMarigold,
                        ),
                    )
                }
                CircleAction(
                    onClick = onStop,
                    icon = Icons.Default.Stop,
                    bg = SaarthiColors.Surface,
                    tint = SaarthiColors.Text2,
                    contentDescription = "Stop",
                )
            }
        }
    }
}

@Composable
private fun PulseRing(progress: Float) {
    // 0..1 → scale 0.85 → 1.4, opacity 0.55 → 0
    val scale = 0.85f + progress * 0.55f
    val alpha = (0.55f * (1f - progress)).coerceAtLeast(0f)
    Box(
        modifier = Modifier
            .size((220 * scale).dp)
            .clip(CircleShape)
            .border(1.5.dp, SaarthiColors.Marigold.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun CircleAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    tint: Color,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, SaarthiColors.Border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(54.dp)) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
