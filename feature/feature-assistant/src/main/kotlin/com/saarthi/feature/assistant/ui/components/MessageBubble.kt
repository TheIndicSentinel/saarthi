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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
    onDelete: () -> Unit,
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
        // Assistant avatar
        if (!isUser) {
            AssistantAvatar(label = avatarLabel, modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
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
                            Modifier.background(
                                Brush.linearGradient(
                                    listOf(
                                        SaarthiColors.Gold.copy(alpha = 0.30f),
                                        SaarthiColors.Gold.copy(alpha = 0.18f),
                                    )
                                )
                            ).border(
                                1.dp,
                                SaarthiColors.Gold.copy(alpha = 0.35f),
                                RoundedCornerShape(
                                    topStart = 20.dp, topEnd = 20.dp,
                                    bottomStart = 20.dp, bottomEnd = 4.dp,
                                )
                            )
                        } else {
                            Modifier.background(SaarthiColors.NavyLight)
                                .border(
                                    1.dp,
                                    SaarthiColors.GlassBorder,
                                    RoundedCornerShape(
                                        topStart = 20.dp, topEnd = 20.dp,
                                        bottomStart = 4.dp, bottomEnd = 20.dp,
                                    )
                                )
                        }
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column {
                    if (message.content.isEmpty() && message.isStreaming) {
                        TypingIndicator()
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                            color = SaarthiColors.TextPrimary,
                        )
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = SaarthiColors.TextPrimary) },
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
                        text = { Text("Delete", color = SaarthiColors.Error) },
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
private fun AssistantAvatar(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(SaarthiColors.Gold.copy(0.25f), SaarthiColors.CyberTeal.copy(0.15f))
                )
            )
            .border(1.dp, SaarthiColors.Gold.copy(0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            ),
            color = SaarthiColors.Gold,
            textAlign = TextAlign.Center,
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
