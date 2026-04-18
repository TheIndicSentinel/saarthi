package com.saarthi.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.saarthiColors

@Composable
fun PackCard(
    title: String,
    description: String,
    icon: Painter,
    accentColor: Color = MaterialTheme.saarthiColors.goldAccent,
    isLocked: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        accentColor = accentColor,
        onClick = if (!isLocked) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (isLocked) SaarthiColors.TextMuted else accentColor,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isLocked) SaarthiColors.TextMuted else SaarthiColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SaarthiColors.TextMuted,
                )
            }
        }
    }
}
