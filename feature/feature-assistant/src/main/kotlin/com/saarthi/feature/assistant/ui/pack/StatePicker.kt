package com.saarthi.feature.assistant.ui.pack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.i18n.IndianStates
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Compact pill showing the user's chosen state (or "Set your state"). Tapping
 * opens [StatePickerSheet]. Shared by the Kisan landing page and the Kisan
 * chat so the user can set or switch state anywhere — deliberately, not just
 * by typing it mid-sentence.
 */
@Composable
fun StateChip(state: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SaarthiColors.MarigoldSoft)
            .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.LocationOn, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(15.dp))
        Text(
            text = state.ifBlank { "Set your state" },
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                color = SaarthiColors.Marigold,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
            ),
            maxLines = 1,
        )
        Icon(Icons.Filled.ArrowDropDown, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatePickerSheet(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SaarthiColors.Surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(
                "Select your state",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                    color = SaarthiColors.Text, fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.size(2.dp))
            Text(
                "Localizes scheme top-ups, bonuses and sowing windows. Central rules still apply everywhere.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                    color = SaarthiColors.Text3, lineHeight = 16.sp,
                ),
            )
            Spacer(Modifier.size(10.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                if (current.isNotBlank()) {
                    item {
                        StateRow("Not set (central rules only)", selected = false) { onSelect(""); onDismiss() }
                    }
                }
                items(IndianStates.all) { st ->
                    StateRow(st, selected = st == current) { onSelect(st); onDismiss() }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun StateRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                color = if (selected) SaarthiColors.Marigold else SaarthiColors.Text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = SaarthiColors.Marigold, modifier = Modifier.size(18.dp))
        }
    }
}
