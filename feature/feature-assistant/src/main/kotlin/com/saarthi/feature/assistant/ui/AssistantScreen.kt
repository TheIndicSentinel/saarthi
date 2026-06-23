package com.saarthi.feature.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.domain.ChatSession
import com.saarthi.feature.assistant.domain.MessageRole
import com.saarthi.feature.assistant.ui.components.AttachmentBottomSheet
import com.saarthi.feature.assistant.ui.components.ChatInputBar
import com.saarthi.feature.assistant.ui.components.MessageBubble
import com.saarthi.feature.assistant.ui.components.ModelStatusChip
import com.saarthi.feature.assistant.viewmodel.AssistantViewModel
import com.saarthi.feature.assistant.viewmodel.PersonalityViewModel
import com.saarthi.feature.assistant.ui.components.PersonalityPickerSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.saarthi.core.ui.components.ShimmerMessagePlaceholder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AssistantScreen(
    onBack: (() -> Unit)? = null,
    // Passed from SaarthiNavHost where MainViewModel already has the stored language loaded.
    // Avoids showing HINDI for one frame while AssistantViewModel's fresh StateFlow catches up.
    initialLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    /** Pre-fills the input when navigating from a home-screen suggestion chip. */
    initialMessage: String = "",
    onNavigateToKnowledge: () -> Unit = {},
    onChangeModel: () -> Unit = {},
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // Use the vmLanguage only if it has moved past the default initial value;
    // otherwise prefer the parent-supplied initialLanguage that is already correct.
    val vmLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val currentLanguage = if (vmLanguage != SupportedLanguage.HINDI) vmLanguage else initialLanguage
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val attachmentSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── Personality Pal ─────────────────────────────────────────────────────
    val personalityVm: PersonalityViewModel = hiltViewModel()
    val activePersonality by personalityVm.selected.collectAsStateWithLifecycle()
    val personalitySupported by personalityVm.supportedForCurrentModel.collectAsStateWithLifecycle()
    var showPersonalitySheet by remember { mutableStateOf(false) }
    val personalitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pre-fill input from a home-screen suggestion chip (fires once on entry).
    LaunchedEffect(initialMessage) {
        if (initialMessage.isNotBlank()) viewModel.onInputChange(initialMessage)
    }

    // Sync drawer open/close with VM state
    LaunchedEffect(uiState.showDrawer) {
        if (uiState.showDrawer) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && uiState.showDrawer) {
            viewModel.closeDrawer()
        }
    }

    // ── Stick-to-bottom auto-follow (ChatGPT / Gemini pattern) ───────────────
    // True bottom of the list = the LAST emitted item (trailing spacer), NOT
    // messages.lastIndex. Scrolling to messages.lastIndex stopped short of the
    // spacer/shimmer, so the end of a long reply stayed below the fold — that's
    // the "scroll doesn't reach the bottom of the response" report.
    suspend fun scrollToBottom() {
        val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        listState.animateScrollToItem(target)
    }
    // "At bottom" = the last item is currently visible. Drives both auto-follow
    // and the scroll-to-bottom FAB.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }

    // New message arrived (user sent, or the assistant bubble appeared) → always
    // snap to the bottom so the new turn is in view.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) scrollToBottom()
    }

    // Streamed tokens growing the last bubble → follow ONLY while the user is
    // still parked at the bottom. The moment they scroll up to re-read, we stop
    // pulling them back down. Re-firing animateScrollToItem on every token was
    // the "scroll gets stuck until generation finishes" bug.
    val streamingContent = messages.lastOrNull()?.takeIf { it.isStreaming }?.content
    LaunchedEffect(streamingContent) {
        if (streamingContent != null && isAtBottom) scrollToBottom()
    }

    // Streaming haptic disabled — buzzing on every token was distracting.
    // A single subtle tick when the first token lands and when generation
    // completes is more in line with modern AI chat UX.
    LaunchedEffect(uiState.isStreaming) {
        if (uiState.isStreaming) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) scrollToBottom()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { scope.launch { snackbarHost.showSnackbar(it) } }
    }

    // One-time battery-optimization whitelist prompt. The native engine
    // generates for tens of seconds at a time; Doze + battery saver will
    // silently kill the foreground service on Samsung/Xiaomi/Oppo OEMs and
    // truncate the reply mid-stream. We ask once, the user's choice sticks.
    var showBatteryOptDialog by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (!com.saarthi.core.common.BatteryOptimizationPrompt.hasPrompted(activity) &&
            !com.saarthi.core.common.BatteryOptimizationPrompt.isIgnoringBatteryOptimizations(activity)
        ) {
            showBatteryOptDialog = true
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.onAttachmentsPicked(uris)
    }
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // Request notification permission on Android 13+ so reminders can fire
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val notifPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        LaunchedEffect(Unit) {
            if (!notifPermission.status.isGranted) notifPermission.launchPermissionRequest()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationsDrawer(
                language = currentLanguage,
                sessions = sessions,
                currentSessionId = currentSessionId,
                onNewChat = { viewModel.newChat() },
                onSelectSession = { viewModel.switchSession(it) },
                onDeleteSession = { viewModel.deleteSession(it) },
                onNavigateToKnowledge = {
                    viewModel.closeDrawer()
                    onNavigateToKnowledge()
                }
            )
        },
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    language = currentLanguage,
                    onBack = onBack,
                    onOpenDrawer = { viewModel.openDrawer() },
                    isStreaming = uiState.isStreaming,
                    tokensPerSecond = uiState.tokensPerSecond,
                    modelReady = uiState.modelReady,
                    activeModelName = uiState.activeModelName,
                    onClearChat = viewModel::showClearDialog,
                    isSearchMode = uiState.isSearchMode,
                    searchQuery = uiState.searchQuery,
                    onSearchToggle = viewModel::toggleSearch,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onChangeModel = onChangeModel,
                    onNewChat = viewModel::newChat,
                    onShowConversations = viewModel::openDrawer,
                    onShareChat = { shareConversation(activity, messages, currentLanguage.shareYouLabel) },
                    onChangePersonality = { showPersonalitySheet = true },
                    activePersonalityEmoji = activePersonality.emoji,
                    activePersonalityName = activePersonality.displayName,
                )
            },
            snackbarHost = {
                // Explicit Snackbar with our own colors — the previous
                // "SnackbarHost(snackbarHost)" relied on Material 3
                // picking up `inverseSurface` / `inverseOnSurface` from
                // our theme, which it wasn't doing reliably and produced
                // a white-on-white invisible toast. Passing the colors
                // directly bypasses every theme path.
                SnackbarHost(snackbarHost) { data ->
                    // Fixed dark "toast" colours in BOTH themes — consistent and
                    // always high-contrast. (Inverse colours flipped between
                    // themes, which looked inconsistent; Surface/Text was
                    // white-on-cream and nearly invisible in light mode.)
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xFF2B2317),
                        contentColor = Color(0xFFF5EEE0),
                        actionColor = Color(0xFFF4A52E),
                        dismissActionContentColor = Color(0xFFBFB6A6),
                    )
                }
            },
            containerColor = SaarthiColors.DeepSpace,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            ) {
                // Background loading indicator
                if (!uiState.modelReady && !uiState.isStreaming) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = SaarthiColors.Gold,
                        trackColor = Color.Transparent,
                    )
                }

                if (messages.isEmpty()) {
                    EmptyState(
                        language = currentLanguage,
                        modifier = Modifier.weight(1f),
                        onSuggestionTap = { viewModel.onInputChange(it) },
                        showDemo = uiState.attachmentsEnabled,
                        onTryDemo = { viewModel.tryDemoDocument() },
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = messages,
                                key = { it.id },
                                // contentType lets Compose reuse the same
                                // composable slot for every bubble, even as
                                // the streaming bubble swaps from placeholder
                                // to actual content.
                                contentType = { "bubble" },
                            ) { msg ->
                                val speakingId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
                                MessageBubble(
                                    message = msg,
                                    language = currentLanguage,
                                    onDelete = { viewModel.deleteMessage(msg.id) },
                                    onRetry = { viewModel.retryResponse(msg.id) },
                                    onListen = { viewModel.toggleSpeak(msg.id, msg.content) },
                                    isSpeaking = speakingId == msg.id,
                                    avatarLabel = currentLanguage.avatarLabel,
                                )
                            }
                            if (uiState.isStreaming && messages.any { it.isStreaming && it.content.isEmpty() }) {
                                item(contentType = "shimmer") { ShimmerMessagePlaceholder() }
                            }
                            item(contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
                        }


                        // Scroll-to-bottom FAB — appears whenever the user has
                        // scrolled away from the bottom (so it also signals that
                        // auto-follow is paused), smooth fade + slide.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isAtBottom,
                            enter = androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(220),
                            ) + androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.tween(220),
                                initialOffsetY = { it / 2 },
                            ),
                            exit = androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(160),
                            ) + androidx.compose.animation.slideOutVertically(
                                animationSpec = androidx.compose.animation.core.tween(160),
                                targetOffsetY = { it / 2 },
                            ),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) {
                            IconButton(
                                onClick = { scope.launch { scrollToBottom() } },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(SaarthiColors.Surface)
                                    .border(1.dp, SaarthiColors.MarigoldBd, CircleShape),
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    null,
                                    tint = SaarthiColors.Marigold,
                                )
                            }
                        }
                    }
                }

                // Reminder confirmation chip — slides in when a reminder is
                // scheduled, auto-dismisses after 5 s via the ViewModel.
                AnimatedVisibility(
                    visible = uiState.scheduledReminder != null,
                    enter = fadeIn() + androidx.compose.animation.slideInVertically { it },
                    exit = fadeOut() + androidx.compose.animation.slideOutVertically { it },
                ) {
                    uiState.scheduledReminder?.let { reminder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(SaarthiColors.Surface)
                                .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text("⏰", fontSize = 16.sp)
                            Spacer(androidx.compose.ui.Modifier.width(8.dp))
                            Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                                Text(
                                    text = reminder.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = SaarthiColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Reminder set ${reminder.timeLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SaarthiColors.TextSecondary,
                                )
                            }
                        }
                    }
                }

                ChatInputBar(
                    inputText = uiState.inputText,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::sendMessage,
                    onAttachClick = {
                        // COMPACT-tier (Gemma 1B) can't usefully process
                        // RAG context inside its 512-tok budget — it
                        // returns "I'm not sure about that" even when
                        // the chunks contain the answer. Block the attach
                        // sheet and tell the user how to fix it.
                        if (!uiState.attachmentsEnabled) {
                            scope.launch {
                                snackbarHost.showSnackbar(
                                    "Attachments need a larger model — switch to Gemma 4 from Settings → Models.",
                                )
                            }
                        } else if (!viewModel.canAttachDocument()) {
                            // Free tier: the per-chat document allowance is used.
                            // Show the Pro upsell instead of the picker.
                            scope.launch {
                                snackbarHost.showSnackbar(
                                    "Free includes 1 document per chat. Unlock Saarthi Pro in Settings for unlimited documents.",
                                )
                            }
                        } else {
                            scope.launch { attachmentSheetState.show() }
                        }
                    },
                    onVoiceClick = {
                        if (micPermission.status.isGranted) {
                            // Dismiss the soft keyboard before the voice overlay
                            // takes over — otherwise the keypad stayed up behind
                            // the overlay when voice was triggered from the input.
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.openVoiceMode()
                        } else {
                            micPermission.launchPermissionRequest()
                        }
                    },
                    onStopStreaming = viewModel::stopGeneration,
                    pendingAttachments = uiState.pendingAttachments,
                    onRemoveAttachment = viewModel::removeAttachment,
                    isStreaming = uiState.isStreaming,
                    isListening = uiState.isListening,
                    hint = currentLanguage.inputHint,
                )
            }
        }
    }

    if (uiState.showAttachmentSheet || attachmentSheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = { scope.launch { attachmentSheetState.hide() } },
            sheetState = attachmentSheetState,
            containerColor = SaarthiColors.Bg2,
            dragHandle = { BottomSheetDefaults.DragHandle(color = SaarthiColors.BorderHi) },
        ) {
            AttachmentBottomSheet(
                language = currentLanguage,
                onPickFiles = { filePicker.launch("*/*") },
                onPickImages = { filePicker.launch("image/*") },
                onDismiss = { scope.launch { attachmentSheetState.hide() } },
            )
        }
    }

    // Personality Pal picker sheet
    if (showPersonalitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showPersonalitySheet = false },
            sheetState = personalitySheetState,
            containerColor = SaarthiColors.Bg2,
            dragHandle = { BottomSheetDefaults.DragHandle(color = SaarthiColors.BorderHi) },
        ) {
            PersonalityPickerSheet(
                personalities = personalityVm.all,
                selectedId = activePersonality.id,
                supportedForCurrentModel = personalitySupported,
                onPick = { id -> personalityVm.select(id) },
                onDismiss = { showPersonalitySheet = false },
            )
        }
    }

    // Voice mode overlay — stays open after listening ends so the user can
    // review the captured text and tap Send.
    if (uiState.showVoiceMode) {
        // Pressing back while the overlay is up dismisses it instead of
        // popping the chat screen.
        androidx.activity.compose.BackHandler(enabled = true) {
            viewModel.closeVoiceMode(clearText = true)
        }
        com.saarthi.feature.assistant.ui.components.VoiceModeOverlay(
            transcribedText = uiState.inputText,
            isListening = uiState.isListening,
            language = currentLanguage,
            onClose = { viewModel.closeVoiceMode(clearText = true) },
            onSend = {
                viewModel.closeVoiceMode(clearText = false)
                if (uiState.inputText.isNotBlank()) viewModel.sendMessage()
            },
            onStop = { viewModel.stopListening() },
            onRestart = { viewModel.startListening() },
        )
    }

    // Search mode back-handler: exit search before letting back propagate to
    // the chat screen pop.
    if (uiState.isSearchMode) {
        androidx.activity.compose.BackHandler(enabled = true) {
            viewModel.toggleSearch()
        }
    }

    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOptDialog = false
                com.saarthi.core.common.BatteryOptimizationPrompt.markPrompted(activity)
            },
            containerColor = SaarthiColors.NavyMid,
            title = { Text(currentLanguage.notifPermTitle, color = SaarthiColors.TextPrimary) },
            text = {
                Text(
                    "Android may pause Saarthi to save battery. Letting the app skip battery " +
                        "optimization keeps long answers from being cut off mid-reply, and makes " +
                        "your reminders fire on time instead of being delayed.",
                    color = SaarthiColors.TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        com.saarthi.core.common.BatteryOptimizationPrompt.requestWhitelist(activity)
                        com.saarthi.core.common.BatteryOptimizationPrompt.markPrompted(activity)
                        showBatteryOptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Marigold),
                ) { Text(currentLanguage.allowLabel, color = SaarthiColors.OnMarigold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.saarthi.core.common.BatteryOptimizationPrompt.markPrompted(activity)
                    showBatteryOptDialog = false
                }) { Text(currentLanguage.notNowLabel, color = SaarthiColors.Text2) }
            },
        )
    }

    if (uiState.showClearDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearDialog,
            containerColor = SaarthiColors.NavyMid,
            title = { Text(currentLanguage.clearChatTitle, color = SaarthiColors.TextPrimary) },
            text = { Text(currentLanguage.clearChatMessage, color = SaarthiColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearChat() },
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Error),
                ) { Text(currentLanguage.clearConfirm) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearDialog) {
                    Text(currentLanguage.cancelLabel, color = SaarthiColors.TextSecondary)
                }
            },
        )
    }
}

// ── Conversations Drawer ─────────────────────────────────────────────────────

@Composable
private fun ConversationsDrawer(
    language: SupportedLanguage,
    sessions: List<ChatSession>,
    currentSessionId: String,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNavigateToKnowledge: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width(300.dp),
        drawerContainerColor = SaarthiColors.NavyDark,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(vertical = 12.dp),
        ) {
            Text(
                language.conversationsLabel,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SaarthiColors.Gold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            FilledTonalButton(
                onClick = onNewChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = SaarthiColors.CyberTeal.copy(alpha = 0.15f),
                    contentColor = SaarthiColors.CyberTeal,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(language.newChat)
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = SaarthiColors.GlassBorder,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        onSelect = { onSelectSession(session.id) },
                        onDelete = { onDeleteSession(session.id) },
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = SaarthiColors.GlassBorder,
            )

            NavigationDrawerItem(
                label = { Text(language.knowledgeTitle, color = SaarthiColors.TextPrimary) },
                selected = false,
                onClick = onNavigateToKnowledge,
                icon = { Icon(Icons.Default.AutoAwesome, null, tint = SaarthiColors.Gold) },
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent,
                    selectedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(session.updatedAt) {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(session.updatedAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) SaarthiColors.Gold.copy(alpha = 0.1f) else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Chat,
            contentDescription = null,
            tint = if (isSelected) SaarthiColors.Gold else SaarthiColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isSelected) SaarthiColors.TextPrimary else SaarthiColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = SaarthiColors.TextMuted,
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = "Delete",
                tint = SaarthiColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── Top Bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Builds a plain-text transcript of the visible conversation (markers stripped)
 * and fires the standard Android share sheet (ACTION_SEND). Industry-standard
 * "share chat" — the receiver gets the questions and answers as readable text.
 */
private fun shareConversation(
    context: android.content.Context,
    messages: List<com.saarthi.feature.assistant.domain.ChatMessage>,
    youLabel: String,
) {
    val transcript = messages
        .filter { !it.isStreaming && it.content.isNotBlank() }
        .joinToString("\n\n") { m ->
            val who = if (m.role == com.saarthi.feature.assistant.domain.MessageRole.USER) youLabel else "Saarthi"
            val text = com.saarthi.feature.assistant.data.ResponseMarkerParser
                .stripForDisplay(m.content, streaming = false)
            "$who: $text"
        }
    if (transcript.isBlank()) return
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Saarthi chat")
        putExtra(android.content.Intent.EXTRA_TEXT, transcript)
    }
    val chooser = android.content.Intent.createChooser(send, null)
        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
}

@Composable
private fun ChatTopBar(
    language: SupportedLanguage,
    onBack: (() -> Unit)?,
    onOpenDrawer: () -> Unit,
    isStreaming: Boolean,
    tokensPerSecond: Float,
    modelReady: Boolean,
    activeModelName: String?,
    onClearChat: () -> Unit,
    isSearchMode: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onChangeModel: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onShowConversations: () -> Unit = {},
    onShareChat: () -> Unit = {},
    onChangePersonality: () -> Unit = {},
    activePersonalityEmoji: String = "🪔",
    activePersonalityName: String = "Saarthi",
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaarthiColors.Bg)
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isSearchMode) {
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SaarthiColors.Text)
            }
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(language.searchHint, color = SaarthiColors.Text3) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = SaarthiColors.Marigold,
                    focusedTextColor = SaarthiColors.Text,
                    unfocusedTextColor = SaarthiColors.Text,
                ),
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = SaarthiColors.Text3)
                        }
                    }
                }
            )
        } else {
            // Back or drawer — icon-only, so the Icon must carry the label.
            IconButton(onClick = onBack ?: onOpenDrawer) {
                Icon(
                    if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    contentDescription = if (onBack != null) "Back" else "Open conversations",
                    tint = SaarthiColors.Text,
                )
            }

            // Model selector pill — clickable, takes user to model picker.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x0DF5EEE3))
                    .border(1.dp, SaarthiColors.Border, RoundedCornerShape(999.dp))
                    .clickable(onClick = onChangeModel)
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (modelReady) SaarthiColors.Jade else SaarthiColors.Marigold),
                )
                Text(
                    text = when {
                        isStreaming -> language.thinkingText
                        modelReady -> activeModelName ?: "AI ready"
                        else -> language.chatOfflineSubtitle
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (isStreaming) SaarthiColors.Jade else SaarthiColors.Text,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isStreaming && tokensPerSecond > 0f) {
                    Text(
                        "${tokensPerSecond.toInt()} tok/s",
                        style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3),
                    )
                }
                // Chevron — signals tappability and matches the spec mock.
                Icon(
                    Icons.Default.ArrowDownward,
                    null,
                    tint = SaarthiColors.Text3,
                    modifier = Modifier.size(14.dp),
                )
            }

            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Default.Search, contentDescription = "Search this chat", tint = SaarthiColors.Text2)
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = SaarthiColors.Text2)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(SaarthiColors.Bg2)
                        .border(1.dp, SaarthiColors.BorderHi, RoundedCornerShape(12.dp)),
                ) {
                    DropdownMenuItem(
                        text = { Text(language.newChat, color = SaarthiColors.Text) },
                        leadingIcon = { Icon(Icons.Default.Add, null, tint = SaarthiColors.Marigold) },
                        onClick = { onNewChat(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(language.conversationsLabel, color = SaarthiColors.Text) },
                        leadingIcon = { Icon(Icons.Default.Chat, null, tint = SaarthiColors.Marigold) },
                        onClick = { onShowConversations(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(language.shareChat, color = SaarthiColors.Text) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = SaarthiColors.Marigold) },
                        onClick = { onShareChat(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(language.changeModel, color = SaarthiColors.Text) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = SaarthiColors.Marigold) },
                        onClick = { onChangeModel(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${language.personaLabel} · ${activePersonalityName}",
                                color = SaarthiColors.Text,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Face, null, tint = SaarthiColors.Marigold)
                        },
                        onClick = { onChangePersonality(); showMenu = false },
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = SaarthiColors.Border,
                    )
                    DropdownMenuItem(
                        text = { Text(language.clearChat, color = SaarthiColors.Rose) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = SaarthiColors.Rose) },
                        onClick = { onClearChat(); showMenu = false },
                    )
                }
            }
        }
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    language: SupportedLanguage,
    modifier: Modifier = Modifier,
    onSuggestionTap: (String) -> Unit = {},
    showDemo: Boolean = false,
    onTryDemo: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.saarthi.core.ui.components.SaarthiLogo(size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            language.askPromptNative,
            style = com.saarthi.core.ui.theme.DisplayAccent.copy(fontSize = 16.sp, color = SaarthiColors.Marigold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            language.emptyChatHeadline,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = SaarthiColors.Text,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            language.emptyChatSubtitle,
            style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text3),
            textAlign = TextAlign.Center,
        )

        // Killer-demo entry: attach a bundled sample document + a ready question
        // in one tap, so a brand-new user sees the document-Q&A wow immediately.
        // Shown only when the active model can use attachments (LARGE tier).
        if (showDemo) {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(SaarthiColors.Marigold.copy(alpha = 0.14f), Color.Transparent),
                        ),
                    )
                    .border(1.dp, SaarthiColors.MarigoldBd, RoundedCornerShape(16.dp))
                    .clickable(onClick = onTryDemo)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📄", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Try the document assistant",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold, color = SaarthiColors.Text, fontSize = 14.sp,
                        ),
                    )
                    Text(
                        "Open a sample PDF and ask a question — one tap",
                        style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3, fontSize = 12.sp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = SaarthiColors.Marigold,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        com.saarthi.core.ui.components.RangoliDivider(width = 100.dp, color = SaarthiColors.Text3)
        Spacer(Modifier.height(18.dp))
        Text(
            language.tryTheseLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                color = SaarthiColors.Text3,
                letterSpacing = 1.4.sp,
            ),
        )
        Spacer(Modifier.height(10.dp))
        SuggestionChips(suggestions = language.suggestions, onSuggestionTap = onSuggestionTap)
    }
}

@Composable
private fun MandalaCanvas(modifier: Modifier = Modifier) {
    val gold = SaarthiColors.Gold
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r1 = size.width * 0.47f
        val r2 = size.width * 0.34f
        val r3 = size.width * 0.20f
        val sw = 1.2f

        drawCircle(color = gold.copy(0.12f), radius = r1, center = Offset(cx, cy), style = Stroke(sw))
        drawCircle(color = gold.copy(0.28f), radius = r2, center = Offset(cx, cy), style = Stroke(sw * 1.5f))
        drawCircle(color = gold.copy(0.45f), radius = r3, center = Offset(cx, cy), style = Stroke(sw * 2f))

        repeat(8) { i ->
            val angle = i * (PI / 4)
            drawLine(
                color = gold.copy(0.09f),
                start = Offset((cx + r3 * cos(angle)).toFloat(), (cy + r3 * sin(angle)).toFloat()),
                end = Offset((cx + r1 * cos(angle)).toFloat(), (cy + r1 * sin(angle)).toFloat()),
                strokeWidth = sw,
            )
        }

        repeat(8) { i ->
            val angle = i * (PI / 4) + (PI / 8)
            drawCircle(
                color = gold.copy(0.55f),
                radius = 3f,
                center = Offset((cx + r2 * cos(angle)).toFloat(), (cy + r2 * sin(angle)).toFloat()),
            )
        }
    }
}

@Composable
private fun FeatureBadge(icon: String, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SaarthiColors.NavyLight)
            .border(1.dp, SaarthiColors.GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = SaarthiColors.TextMuted)
    }
}

@Composable
private fun SuggestionChips(
    suggestions: List<String>,
    onSuggestionTap: (String) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        suggestions.take(4).chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SaarthiColors.NavyLight)
                            .border(1.dp, SaarthiColors.GlassBorder, RoundedCornerShape(14.dp))
                            .clickable { onSuggestionTap(suggestion) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelMedium,
                            color = SaarthiColors.TextSecondary,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
