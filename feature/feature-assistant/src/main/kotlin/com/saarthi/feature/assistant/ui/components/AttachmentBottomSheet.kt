package com.saarthi.feature.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Attachment bottom sheet — 4-tile grid (Camera / Photo / Document / Voice memo).
 * Matches SAARTHI_HANDOFF.md §B.6 AttachmentMenu.
 */
@Composable
fun AttachmentBottomSheet(
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp, top = 4.dp),
    ) {
        Text(
            "Attach",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Files stay on your device — never uploaded",
            style = MaterialTheme.typography.bodySmall.copy(
                color = SaarthiColors.Text3,
                fontSize = 12.sp,
            ),
        )
        Spacer(Modifier.height(18.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachTile(
                icon = Icons.Default.CameraAlt,
                label = "Camera",
                sub = "Take a photo",
                tone = SaarthiColors.Marigold,
                toneBg = SaarthiColors.MarigoldSoft,
                onClick = { onPickImages(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                icon = Icons.Default.Image,
                label = "Photo",
                sub = "From gallery",
                tone = SaarthiColors.Terracotta,
                toneBg = SaarthiColors.TerracottaSoft,
                onClick = { onPickImages(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachTile(
                icon = Icons.Default.Description,
                label = "Document",
                sub = "PDF · DOC · TXT",
                tone = SaarthiColors.Indigo,
                toneBg = SaarthiColors.IndigoSoft,
                onClick = { onPickFiles(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
            AttachTile(
                icon = Icons.Default.Mic,
                label = "Voice memo",
                sub = "Record audio",
                tone = SaarthiColors.Rose,
                toneBg = SaarthiColors.RoseSoft,
                onClick = { onPickFiles(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AttachTile(
    icon: ImageVector,
    label: String,
    sub: String,
    tone: Color,
    toneBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(toneBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tone, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaarthiColors.Text,
                ),
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = SaarthiColors.Text3,
                ),
            )
        }
    }
}
