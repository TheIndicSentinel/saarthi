package com.saarthi.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun SaarthiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SaarthiColors.Gold,
            contentColor = SaarthiColors.DeepSpace,
            disabledContainerColor = SaarthiColors.GoldDim,
            disabledContentColor = SaarthiColors.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SaarthiOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SaarthiColors.Gold,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, SaarthiColors.Gold.copy(alpha = 0.6f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
