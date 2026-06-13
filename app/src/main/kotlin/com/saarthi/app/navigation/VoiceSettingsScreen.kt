package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.app.VoiceSettingsViewModel
import com.saarthi.core.ui.components.SaarthiTopBar
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Simple voice style screen — Male or Female.
 *
 * The voice itself was downloaded automatically during onboarding (or is
 * downloading in the background). Users don't need to know about packs,
 * file sizes, or download mechanics. They just pick a voice they prefer.
 *
 * If neural TTS isn't available on this device, a brief honest note is shown
 * and the toggle is hidden — no dead-end "download" option shown.
 */
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val gender by viewModel.voiceGender.collectAsStateWithLifecycle()
    val installed by viewModel.installedPackIds.collectAsStateWithLifecycle()
    val hasVoice = installed.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.Bg),
    ) {
        SaarthiTopBar(title = "Voice style", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !viewModel.isNeuralSupported -> {
                    // LOW/MINIMAL device — just tell the truth, no options
                    Text(
                        "Saarthi uses your phone's built-in voice. It works offline " +
                            "and needs no setup — the built-in voice is what's available on this device.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
                    )
                }

                !hasVoice -> {
                    // Neural supported but download hasn't finished yet (or was skipped)
                    Text(
                        "An Indian voice is being prepared in the background. " +
                            "Once ready, come back here to choose male or female.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
                    )
                }

                else -> {
                    // Voice is ready — show the simple Male / Female choice
                    Text(
                        "Choose the voice Saarthi speaks in.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
                    )
                    Spacer(Modifier.height(4.dp))
                    GenderOption(
                        label = "Male",
                        description = "Calm, clear and friendly",
                        selected = gender == "male",
                        onClick = { viewModel.setGender("male") },
                    )
                    GenderOption(
                        label = "Female",
                        description = "Warm and natural",
                        selected = gender == "female",
                        onClick = { viewModel.setGender("female") },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenderOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) SaarthiColors.JadeSoft else SaarthiColors.Surface
            )
            .border(
                1.dp,
                if (selected) SaarthiColors.JadeBd else SaarthiColors.Border,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.RecordVoiceOver,
            null,
            tint = if (selected) SaarthiColors.Jade else SaarthiColors.Text3,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) SaarthiColors.Jade else SaarthiColors.Text,
                ),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                null,
                tint = SaarthiColors.Jade,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
