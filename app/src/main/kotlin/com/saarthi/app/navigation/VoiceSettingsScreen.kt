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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.saarthi.feature.assistant.data.VoiceCatalog
import com.saarthi.feature.assistant.data.VoicePackManager

/**
 * Pick Saarthi's Hindi voice (Male / Female). The voice usually downloads
 * automatically right after onboarding; this screen shows live progress, an
 * error + retry path if that auto-download failed, and lets the user switch
 * between male and female. On devices that can't run neural TTS, a short honest
 * note is shown instead — no download offered.
 */
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val gender by viewModel.voiceGender.collectAsStateWithLifecycle()
    val installed by viewModel.installedPackIds.collectAsStateWithLifecycle()

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
            if (!viewModel.isNeuralSupported) {
                Text(
                    "Saarthi uses your phone's built-in voice. It works offline with no " +
                        "setup — that's what's available on this device.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
                )
                return@Column
            }

            Text(
                "Choose the Hindi voice Saarthi reads replies in. Each voice is a one-time " +
                    "~20 MB download and then works fully offline.",
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
            )
            Spacer(Modifier.height(2.dp))

            VoiceCatalog.entries.forEach { pack ->
                val state by viewModel.stateFor(pack.id).collectAsStateWithLifecycle()
                VoiceRow(
                    pack       = pack,
                    state      = state,
                    installed  = pack.id in installed,
                    selected   = pack.gender == gender && pack.id in installed,
                    onDownload = { viewModel.download(pack.id) },
                    onSelect   = { viewModel.selectGender(pack.gender) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Voice: Piper (MIT) · Engine: sherpa-onnx (Apache 2.0)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text3,
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

@Composable
private fun VoiceRow(
    pack: VoiceCatalog.VoicePack,
    state: VoicePackManager.DownloadState,
    installed: Boolean,
    selected: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
) {
    val borderColor = if (selected) SaarthiColors.JadeBd else SaarthiColors.Border
    val bgColor = if (selected) SaarthiColors.JadeSoft else SaarthiColors.Surface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .let { if (installed) it.clickable(onClick = onSelect) else it }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                null,
                tint = if (selected) SaarthiColors.Jade else SaarthiColors.Text3,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pack.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) SaarthiColors.Jade else SaarthiColors.Text,
                    ),
                )
                Text(
                    if (installed) "Installed" else "~${pack.approximateSizeMb} MB download",
                    style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3),
                )
            }

            when {
                selected -> Icon(Icons.Outlined.Check, null, tint = SaarthiColors.Jade, modifier = Modifier.size(20.dp))
                installed -> Text(
                    "Select",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = SaarthiColors.Jade, fontWeight = FontWeight.SemiBold,
                    ),
                )
                state is VoicePackManager.DownloadState.Downloading ||
                state is VoicePackManager.DownloadState.Extracting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), color = SaarthiColors.Jade, strokeWidth = 2.dp,
                    )
                }
                state is VoicePackManager.DownloadState.Error -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Marigold),
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                        Text("  Retry")
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Jade),
                    ) {
                        Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                        Text("  Get")
                    }
                }
            }
        }

        // Progress / error detail line below the row.
        when (state) {
            is VoicePackManager.DownloadState.Downloading -> {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = SaarthiColors.Jade,
                )
            }
            is VoicePackManager.DownloadState.Extracting -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Installing…",
                    style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3),
                )
            }
            is VoicePackManager.DownloadState.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Download failed — tap Retry. (${state.message})",
                    style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Rose),
                )
            }
            else -> {}
        }
    }
}
