package com.saarthi.feature.assistant.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.AttachedFile

@Composable
fun AttachmentChip(
    file: AttachedFile,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SaarthiColors.NavyLight)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (file.isImage) {
            AsyncImage(
                model = file.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Text(file.fileIcon, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextSecondary,
            maxLines = 1,
        )
        Text(
            text = file.displaySize,
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextMuted,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(
                Icons.Default.Close, contentDescription = "Remove",
                tint = SaarthiColors.TextMuted, modifier = Modifier.size(14.dp),
            )
        }
    }
}

// Compact read-only chip shown inside a sent message bubble
@Composable
fun SentAttachmentChip(file: AttachedFile, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SaarthiColors.NavyMid)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (file.isImage) {
            AsyncImage(
                model = file.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Text(file.fileIcon, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            file.name,
            style = MaterialTheme.typography.labelMedium,
            color = SaarthiColors.TextMuted,
            maxLines = 1,
        )
    }
}
