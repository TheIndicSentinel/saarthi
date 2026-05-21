package com.saarthi.feature.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.AttachedFile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onStopStreaming: () -> Unit,
    pendingAttachments: List<AttachedFile>,
    onRemoveAttachment: (AttachedFile) -> Unit,
    isStreaming: Boolean,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    hint: String = "Ask Saarthi anything…",
) {
    val micPulse = rememberInfiniteTransition(label = "mic_pulse")
    val micAlpha by micPulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mic_alpha",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isListening -> SaarthiColors.Rose
            else -> SaarthiColors.BorderHi
        },
        label = "border",
    )

    val hasText = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = hasText && !isStreaming

    // Parent chat column already applies the union of navigationBars + ime
    // insets, so this component should add NO bottom system inset of its own —
    // doing so would create a gap above the keyboard.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SaarthiColors.Bg)
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
    ) {
        AnimatedVisibility(
            visible = pendingAttachments.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                pendingAttachments.forEach { file ->
                    AttachmentChip(file = file, onRemove = { onRemoveAttachment(file) })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Attach button — sits OUTSIDE the input pill so the pill stays
            // clean (voice → send only).
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SaarthiColors.Surface)
                    .border(1.dp, SaarthiColors.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onAttachClick, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach",
                        tint = SaarthiColors.Text2,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Pill input row — single trailing icon (mic → send → stop).
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(SaarthiColors.Surface)
                    .border(1.dp, borderColor, RoundedCornerShape(28.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
            // Text field
            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isListening) "Listening…" else hint,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = SaarthiColors.Text3,
                            fontSize = 14.sp,
                        ),
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = SaarthiColors.Text,
                    unfocusedTextColor = SaarthiColors.Text,
                    cursorColor = SaarthiColors.Marigold,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.sp,
                    color = SaarthiColors.Text,
                    fontWeight = FontWeight.Normal,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 6,
            )

            // Trailing — Stop while streaming; Send when text; otherwise Mic.
            when {
                isStreaming -> {
                    TrailingCircleButton(
                        bg = SaarthiColors.RoseSoft,
                        iconTint = SaarthiColors.Rose,
                        icon = Icons.Default.Stop,
                        contentDescription = "Stop",
                        onClick = onStopStreaming,
                    )
                }
                hasText -> {
                    TrailingCircleButton(
                        bg = SaarthiColors.Marigold,
                        iconTint = SaarthiColors.OnMarigold,
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        onClick = { if (canSend) onSend() },
                    )
                }
                else -> {
                    val micBg = if (isListening)
                        SaarthiColors.Rose.copy(alpha = micAlpha * 0.4f)
                    else
                        Color(0x10F5EEE3)
                    TrailingCircleButton(
                        bg = micBg,
                        iconTint = if (isListening) SaarthiColors.Rose else SaarthiColors.Text2,
                        icon = Icons.Default.Mic,
                        contentDescription = "Voice",
                        onClick = onVoiceClick,
                    )
                }
            }
            } // end pill Row
        } // end outer Row (attach + pill)
    }
}

@Composable
private fun TrailingCircleButton(
    bg: Color,
    iconTint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(18.dp))
        }
    }
}
