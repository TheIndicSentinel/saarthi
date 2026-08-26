package com.saarthi.feature.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.data.DisplaySource

/**
 * A5 — readable Sources block: header + file chips (icon, title, page/overview).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesChipsRow(
    header: String,
    sources: List<DisplaySource>,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return
    Column(modifier = modifier.padding(top = 4.dp)) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = SaarthiColors.TextMuted,
            ),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { source ->
                SourceCitationChip(source = source)
            }
        }
    }
}

@Composable
private fun SourceCitationChip(
    source: DisplaySource,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SaarthiColors.IndigoSoft)
            .border(1.dp, SaarthiColors.IndigoBd, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = SaarthiColors.Indigo,
        )
        Column {
            Text(
                text = source.docTitle,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    color = SaarthiColors.Indigo,
                ),
                maxLines = 2,
            )
            Text(
                text = source.location,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    color = SaarthiColors.Text2,
                ),
                maxLines = 1,
            )
        }
    }
}
