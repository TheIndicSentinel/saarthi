package com.saarthi.feature.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    language: com.saarthi.core.i18n.SupportedLanguage,
    onDelete: () -> Unit,
    onRetry: () -> Unit = {},
    onListen: () -> Unit = {},
    isSpeaking: Boolean = false,
    modifier: Modifier = Modifier,
    avatarLabel: String = "स",
) {
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    val isUser = message.role == MessageRole.USER
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.78f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Assistant avatar — flame at rest, petal-loader while streaming.
        if (!isUser) {
            AssistantAvatar(
                label = avatarLabel,
                isStreaming = message.isStreaming,
                modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
            )
        } else {
            Spacer(Modifier.width(48.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // Attachments above bubble
            if (message.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    message.attachments.forEach { file ->
                        SentAttachmentChip(file = file)
                    }
                }
            }

            // Bubble
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isUser) 20.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 20.dp,
                        )
                    )
                    .then(
                        if (isUser) {
                            Modifier.background(SaarthiColors.Gold.copy(alpha = 0.15f))
                        } else {
                            Modifier.background(SaarthiColors.NavyMid)
                        }
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(
                    modifier = if (!isUser && message.isStreaming) {
                        // Live region announces streaming updates to TalkBack
                        // without re-reading the bubble character-by-character.
                        Modifier.semantics {
                            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                            contentDescription = "Saarthi is generating a response"
                        }
                    } else Modifier,
                ) {
                    if (message.content.isEmpty() && message.isStreaming) {
                        TypingIndicator()
                    } else if (isUser) {
                        // User text is verbatim — never render markdown for what they typed.
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                            color = SaarthiColors.TextPrimary,
                        )
                    } else {
                        // Assistant output is markdown — render bold/italic/lists/code.
                        MarkdownText(
                            text = message.content,
                            color = SaarthiColors.TextPrimary,
                        )
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(language.copyLabel, color = SaarthiColors.TextPrimary) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                null,
                                tint = SaarthiColors.TextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(language.deleteLabel, color = SaarthiColors.Error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = SaarthiColors.Error,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { onDelete(); showMenu = false },
                    )
                }
            }

            // Copy + Retry + Listen actions under each completed AI bubble.
            if (!isUser && message.content.isNotEmpty() && !message.isStreaming) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BubbleActionChip(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy",
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                    )
                    BubbleActionChip(
                        icon = Icons.Default.Refresh,
                        label = "Retry",
                        onClick = onRetry,
                    )
                    BubbleActionChip(
                        icon = if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        label = if (isSpeaking) "Stop" else "Listen",
                        onClick = onListen,
                        highlighted = isSpeaking,
                    )
                }
            }

            // Token count (debug aid — timestamp removed per UX preference)
            if (message.tokenCount > 0 && !isUser) {
                Text(
                    "${message.tokenCount}t",
                    modifier = Modifier.padding(top = 3.dp, start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = SaarthiColors.TextMuted,
                )
            }
        }

        if (isUser) {
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AssistantAvatar(
    @Suppress("UNUSED_PARAMETER") label: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // "Flame at rest, petals while thinking" — the avatar swap is the
    // app's primary signal that the model is generating. The avatar
    // tile (circle, soft marigold wash, marigold border) is identical
    // in both states so the swap reads as the same character changing
    // expression, not a new bubble.
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(SaarthiColors.MarigoldSoft)
            .border(1.dp, SaarthiColors.MarigoldBd, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isStreaming) {
            com.saarthi.core.ui.components.SaarthiPetalLoader(size = 28.dp)
        } else {
            com.saarthi.core.ui.components.SaarthiFlame(size = 24.dp)
        }
    }
}

@Composable
private fun BubbleActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val borderColor = if (highlighted) SaarthiColors.MarigoldBd else SaarthiColors.Border
    val bg = if (highlighted) SaarthiColors.MarigoldSoft else androidx.compose.ui.graphics.Color.Transparent
    val contentColor = if (highlighted) SaarthiColors.Marigold else SaarthiColors.Text3
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()  // expands hit-target to ≥ 48dp without changing visuals
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(
                onClick = onClick,
                role = androidx.compose.ui.semantics.Role.Button,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Decorative icon; the adjacent label is what the screen reader announces.
        Icon(icon, null, tint = contentColor, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = contentColor,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = i * 160, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SaarthiColors.Gold.copy(alpha = alpha))
            )
        }
    }
}
