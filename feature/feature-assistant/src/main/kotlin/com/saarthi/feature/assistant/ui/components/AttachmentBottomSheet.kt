package com.saarthi.feature.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun AttachmentBottomSheet(
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Attach",
            style = MaterialTheme.typography.titleMedium,
            color = SaarthiColors.TextPrimary,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AttachOption(
                icon = Icons.Default.Image,
                label = "Photo / Image",
                tint = SaarthiColors.CyberTeal,
                modifier = Modifier.weight(1f),
                onClick = { onPickImages(); onDismiss() },
            )
            AttachOption(
                icon = Icons.Default.Description,
                label = "Document / PDF",
                tint = SaarthiColors.Gold,
                modifier = Modifier.weight(1f),
                onClick = { onPickFiles(); onDismiss() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AttachOption(
                icon = Icons.Default.TextSnippet,
                label = "Text / Code File",
                tint = SaarthiColors.CyberTeal,
                modifier = Modifier.weight(1f),
                onClick = { onPickFiles(); onDismiss() },
            )
            // Placeholder for future camera/scan integration
            AttachOption(
                icon = Icons.Default.TextSnippet,
                label = "Any File",
                tint = SaarthiColors.TextMuted,
                modifier = Modifier.weight(1f),
                onClick = { onPickFiles(); onDismiss() },
            )
        }
    }
}

@Composable
private fun AttachOption(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.NavyLight)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = SaarthiColors.TextSecondary)
    }
}
