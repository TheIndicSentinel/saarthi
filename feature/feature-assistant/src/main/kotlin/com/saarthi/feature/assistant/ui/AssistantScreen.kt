package com.saarthi.feature.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.MessageRole
import com.saarthi.feature.assistant.ui.components.AttachmentBottomSheet
import com.saarthi.feature.assistant.ui.components.ChatInputBar
import com.saarthi.feature.assistant.ui.components.MessageBubble
import com.saarthi.feature.assistant.ui.components.ModelStatusChip
import com.saarthi.feature.assistant.viewmodel.AssistantViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AssistantScreen(
    onBack: (() -> Unit)? = null,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val attachmentSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { scope.launch { snackbarHost.showSnackbar(it) } }
    }

    // File picker — multi-select, any type
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.onAttachmentsPicked(uris) }

    // Mic permission
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = SaarthiColors.DeepSpace,
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {

            // ── Top Bar ──────────────────────────────────────────────────────
            ChatTopBar(
                onBack = onBack,
                isStreaming = uiState.isStreaming,
                tokensPerSecond = uiState.tokensPerSecond,
                modelReady = uiState.modelReady,
                onClearChat = viewModel::showClearDialog,
            )

            // ── Empty state ───────────────────────────────────────────────────
            if (messages.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                // ── Messages ──────────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onDelete = { viewModel.deleteMessage(msg.id) },
                        )
                    }
                }
            }

            // ── Input Bar ─────────────────────────────────────────────────────
            ChatInputBar(
                inputText = uiState.inputText,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onAttachClick = {
                    scope.launch { attachmentSheetState.show() }
                },
                onVoiceClick = {
                    if (micPermission.status.isGranted) {
                        if (uiState.isListening) viewModel.stopListening()
                        else viewModel.startListening()
                    } else {
                        micPermission.launchPermissionRequest()
                    }
                },
                onStopStreaming = viewModel::stopListening,
                pendingAttachments = uiState.pendingAttachments,
                onRemoveAttachment = viewModel::removeAttachment,
                isStreaming = uiState.isStreaming,
                isListening = uiState.isListening,
            )
        }
    }

    // ── Attachment Bottom Sheet ───────────────────────────────────────────────
    if (uiState.showAttachmentSheet || attachmentSheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { attachmentSheetState.hide() }
            },
            sheetState = attachmentSheetState,
            containerColor = SaarthiColors.NavyMid,
            dragHandle = { BottomSheetDefaults.DragHandle(color = SaarthiColors.GlassBorder) },
        ) {
            AttachmentBottomSheet(
                onPickFiles = { filePicker.launch("*/*") },
                onPickImages = { filePicker.launch("image/*") },
                onDismiss = { scope.launch { attachmentSheetState.hide() } },
            )
        }
    }

    // ── Clear Chat Dialog ─────────────────────────────────────────────────────
    if (uiState.showClearDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearDialog,
            containerColor = SaarthiColors.NavyMid,
            title = { Text("Clear conversation?", color = SaarthiColors.TextPrimary) },
            text = { Text("All messages will be deleted.", color = SaarthiColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearChat() },
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Error),
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearDialog) {
                    Text("Cancel", color = SaarthiColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun ChatTopBar(
    onBack: (() -> Unit)?,
    isStreaming: Boolean,
    tokensPerSecond: Float,
    modelReady: Boolean,
    onClearChat: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SaarthiColors.NavyDark, SaarthiColors.DeepSpace.copy(alpha = 0f))
                )
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SaarthiColors.TextSecondary)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }

        // Brand mark
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = SaarthiColors.Gold,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Saarthi", style = MaterialTheme.typography.titleMedium, color = SaarthiColors.Gold)
            Text("Gemma 2B · 100% Offline", style = MaterialTheme.typography.labelMedium, color = SaarthiColors.TextMuted)
        }

        ModelStatusChip(
            isStreaming = isStreaming,
            tokensPerSecond = tokensPerSecond,
            modelReady = modelReady,
        )

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, null, tint = SaarthiColors.TextSecondary)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Clear chat", color = SaarthiColors.Error) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = SaarthiColors.Error) },
                    onClick = { onClearChat(); showMenu = false },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🪔", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(20.dp))
        Text(
            "Ask me anything",
            style = MaterialTheme.typography.headlineMedium,
            color = SaarthiColors.Gold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Attach files · Use voice · Completely private\nPowered by Gemma 2B on-device",
            style = MaterialTheme.typography.bodyMedium,
            color = SaarthiColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        // Suggestion chips
        SuggestionChips()
    }
}

@Composable
private fun SuggestionChips() {
    val suggestions = listOf(
        "Explain quantum computing",
        "Write a Python script",
        "Summarize an attached PDF",
        "Help me with my finances",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SaarthiColors.GlassSurface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelMedium,
                            color = SaarthiColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
