package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val installed by viewModel.installedPackIds.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.Bg),
    ) {
        SaarthiTopBar(title = "Indian Voice", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.isNeuralSupported) {
                InfoCard(
                    "System voice only on this device",
                    "Indian neural voices need at least 6 GB RAM. This device uses the " +
                        "built-in Android voice, which still works offline — just with a " +
                        "more generic accent.",
                )
                return@Column
            }

            Text(
                "Download a free Indian voice to hear Saarthi speak in a more natural " +
                    "accent. Each voice is ~64 MB and works completely offline after download.",
                style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2),
            )

            VoiceCatalog.entries.forEach { pack ->
                val state by viewModel.stateFor(pack.id).collectAsStateWithLifecycle()
                val isInstalled = pack.id in installed

                VoicePackRow(
                    pack      = pack,
                    state     = state,
                    installed = isInstalled,
                    onDownload = { viewModel.download(pack.id) },
                    onRemove   = { viewModel.remove(pack.id) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Voices: Piper (MIT licence) · Runtime: sherpa-onnx (Apache 2.0)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = SaarthiColors.Text3,
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

@Composable
private fun VoicePackRow(
    pack: VoiceCatalog.VoicePack,
    state: VoicePackManager.DownloadState,
    installed: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.RecordVoiceOver,
                        null,
                        tint = SaarthiColors.Jade,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            pack.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = SaarthiColors.Text,
                            ),
                        )
                        Text(
                            "~${pack.approximateSizeMb} MB · Hindi neural voice",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SaarthiColors.Text3,
                            ),
                        )
                    }
                }

                when {
                    installed && state is VoicePackManager.DownloadState.Idle ||
                    installed && state is VoicePackManager.DownloadState.Ready -> {
                        OutlinedButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SaarthiColors.Text3,
                            ),
                        ) { Text("Remove") }
                    }
                    state is VoicePackManager.DownloadState.Downloading ||
                    state is VoicePackManager.DownloadState.Extracting -> { /* progress below */ }
                    else -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SaarthiColors.Jade,
                            ),
                        ) {
                            Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                            Text("  Download")
                        }
                    }
                }
            }

            when (state) {
                is VoicePackManager.DownloadState.Downloading -> {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { state.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = SaarthiColors.Jade,
                    )
                    Text(
                        "${state.progressPct}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3),
                    )
                }
                is VoicePackManager.DownloadState.Extracting -> {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = SaarthiColors.Jade,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Installing…",
                            style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3),
                        )
                    }
                }
                is VoicePackManager.DownloadState.Ready -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "✓ Installed — Saarthi will use this voice for Hindi",
                        style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Jade),
                    )
                }
                is VoicePackManager.DownloadState.Error -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Download failed: ${state.message}",
                        style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Rose),
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = SaarthiColors.Text,
            ),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text2),
        )
    }
}
