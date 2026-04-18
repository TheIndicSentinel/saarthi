package com.saarthi.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun UserChatBubble(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(SaarthiColors.Gold.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = SaarthiColors.TextPrimary,
            )
        }
    }
}

@Composable
fun AssistantChatBubble(
    message: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Saarthi avatar dot
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(SaarthiColors.Gold.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("S", style = MaterialTheme.typography.labelMedium, color = SaarthiColors.Gold)
        }

        Column {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(SaarthiColors.GlassSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SaarthiColors.TextSecondary,
                )
            }
            if (isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 4.dp).size(12.dp),
                    color = SaarthiColors.Gold,
                    strokeWidth = 1.5.dp,
                )
            }
        }
    }
}
