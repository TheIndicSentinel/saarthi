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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
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
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mic_alpha",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isListening -> SaarthiColors.Error
            isStreaming -> SaarthiColors.CyberTeal.copy(alpha = 0.7f)
            else -> SaarthiColors.GlassBorder
        },
        label = "border",
    )

    val canSend = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) && !isStreaming

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        SaarthiColors.DeepSpace.copy(alpha = 0f),
                        SaarthiColors.DeepSpace,
                    )
                )
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .navigationBarsPadding(),
    ) {
        // Pending attachments
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

        // Pill input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(SaarthiColors.NavyLight)
                .border(1.dp, borderColor, RoundedCornerShape(28.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Attach
            IconButton(
                onClick = onAttachClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SaarthiColors.GlassSurface),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Attach",
                    tint = SaarthiColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Text field
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

            // Voice or Stop
            if (isStreaming) {
                IconButton(
                    onClick = onStopStreaming,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SaarthiColors.Error.copy(alpha = 0.15f)),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = SaarthiColors.Error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier
                        .size(40.dp)
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
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Send button
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) {
                            Brush.linearGradient(
                                listOf(SaarthiColors.Gold, SaarthiColors.GoldDim)
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(SaarthiColors.GlassSurface, SaarthiColors.GlassSurface)
                            )
                        }
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) SaarthiColors.DeepSpace else SaarthiColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
