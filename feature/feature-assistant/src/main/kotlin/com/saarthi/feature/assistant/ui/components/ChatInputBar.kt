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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
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
) {
    val micPulse = rememberInfiniteTransition(label = "mic_pulse")
    val micAlpha by micPulse.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "mic_alpha",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isListening -> SaarthiColors.Error
            isStreaming -> SaarthiColors.CyberTeal.copy(alpha = 0.6f)
            else -> SaarthiColors.GlassBorder
        },
        label = "border",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(SaarthiColors.NavyMid)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Pending attachments strip
        AnimatedVisibility(
            visible = pendingAttachments.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                pendingAttachments.forEach { file ->
                    AttachmentChip(file = file, onRemove = { onRemoveAttachment(file) })
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Attach button
            IconButton(
                onClick = onAttachClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SaarthiColors.GlassSurface),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Attach", tint = SaarthiColors.TextSecondary)
            }

            // Text input
            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isListening) "Listening…" else "Message Saarthi…",
                        color = SaarthiColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = SaarthiColors.TextPrimary,
                    unfocusedTextColor = SaarthiColors.TextPrimary,
                    cursorColor = SaarthiColors.Gold,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 6,
            )

            // Voice / Stop
            if (isStreaming) {
                IconButton(
                    onClick = onStopStreaming,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.Error.copy(alpha = 0.15f)),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = SaarthiColors.Error)
                }
            } else {
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) SaarthiColors.Error.copy(alpha = micAlpha * 0.3f)
                            else SaarthiColors.GlassSurface
                        ),
                ) {
                    Icon(
                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice",
                        tint = if (isListening) SaarthiColors.Error else SaarthiColors.TextSecondary,
                    )
                }
            }

            // Send
            val canSend = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) && !isStreaming
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (canSend) SaarthiColors.Gold else SaarthiColors.GoldDim),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) SaarthiColors.DeepSpace else SaarthiColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
