package com.saarthi.feature.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Assistant avatar
        if (!isUser) {
            AvatarDot(modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // Attachments row (shown above the bubble)
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
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp,
                        )
                    )
                    .background(
                        if (isUser) SaarthiColors.Gold.copy(alpha = 0.12f)
                        else SaarthiColors.NavyLight
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    if (message.content.isEmpty() && message.isStreaming) {
                        TypingIndicator()
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) SaarthiColors.TextPrimary else SaarthiColors.TextSecondary,
                        )
                    }
                }

                // Context menu
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = SaarthiColors.TextPrimary) },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, null, tint = SaarthiColors.TextSecondary, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            showMenu = false
                        },
                        colors = MenuDefaults.itemColors(textColor = SaarthiColors.TextPrimary),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = SaarthiColors.Error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = SaarthiColors.Error, modifier = Modifier.size(16.dp))
                        },
                        onClick = { onDelete(); showMenu = false },
                    )
                }
            }

            // Timestamp + token count
            Row(
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = SaarthiColors.TextMuted,
                )
                if (message.tokenCount > 0) {
                    Text(
                        "· ${message.tokenCount} tokens",
                        style = MaterialTheme.typography.labelMedium,
                        color = SaarthiColors.TextMuted,
                    )
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun AvatarDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(SaarthiColors.Gold.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("🪔", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 150, easing = LinearEasing),
                    RepeatMode.Reverse
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(SaarthiColors.Gold.copy(alpha = alpha))
            )
        }
    }
}
