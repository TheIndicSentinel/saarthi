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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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
    onNavigateToKnowledge: () -> Unit = {},
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

    // Sync drawer open/close with VM state
    LaunchedEffect(uiState.showDrawer) {
        if (uiState.showDrawer) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && uiState.showDrawer) {
            viewModel.closeDrawer()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Immersive Haptic Feedback during streaming
    LaunchedEffect(messages) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg?.isStreaming == true && lastMsg.content.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { scope.launch { snackbarHost.showSnackbar(it) } }
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
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
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
                        onSuggestionTap = { viewModel.onInputChange(it) }
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(
                                    message = msg,
                                    onDelete = { viewModel.deleteMessage(msg.id) },
                                    avatarLabel = currentLanguage.avatarLabel,
                                )
                            }
                            if (uiState.isStreaming && messages.any { it.isStreaming && it.content.isEmpty() }) {
                                item { ShimmerMessagePlaceholder() }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }


                        // Scroll-to-bottom FAB
                        val showScrollFab = remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showScrollFab.value,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        ) {
                            IconButton(
                                onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(SaarthiColors.NavyLight.copy(alpha = 0.9f))
                                    .border(1.dp, SaarthiColors.Gold.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    null,
                                    tint = SaarthiColors.Gold
                                )
                            }
                        }
                    }
                }

                ChatInputBar(
                    inputText = uiState.inputText,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::sendMessage,
                    onAttachClick = { scope.launch { attachmentSheetState.show() } },
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
                    hint = currentLanguage.inputHint,
                )
            }
        }
    }

    if (uiState.showAttachmentSheet || attachmentSheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = { scope.launch { attachmentSheetState.hide() } },
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

    if (uiState.showClearDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearDialog,
            containerColor = SaarthiColors.NavyMid,
            title = { Text("Clear conversation?", color = SaarthiColors.TextPrimary) },
            text = { Text("All messages in this chat will be deleted.", color = SaarthiColors.TextSecondary) },
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
                label = { Text("Saarthi Knowledge", color = SaarthiColors.TextPrimary) },
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
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaarthiColors.NavyDark.copy(alpha = 0.95f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSearchMode) {
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SaarthiColors.TextSecondary)
            }
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search...", color = SaarthiColors.TextMuted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = SaarthiColors.Gold,
                    focusedTextColor = SaarthiColors.TextPrimary,
                    unfocusedTextColor = SaarthiColors.TextPrimary,
                ),
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, null, tint = SaarthiColors.TextMuted)
                        }
                    }
                }
            )
        } else {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SaarthiColors.TextSecondary)
                }
            } else {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, null, tint = SaarthiColors.TextSecondary)
                }
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(SaarthiColors.Gold.copy(0.2f), SaarthiColors.CyberTeal.copy(0.1f))
                        )
                    )
                    .border(1.dp, SaarthiColors.Gold.copy(0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    language.avatarLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = SaarthiColors.Gold,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    language.appName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SaarthiColors.Gold,
                )
                val subColor = if (isStreaming) SaarthiColors.CyberTeal else SaarthiColors.TextMuted
                val subText = when {
                    isStreaming -> language.thinkingText
                    modelReady -> activeModelName ?: "AI Assistant"
                    else -> language.chatOfflineSubtitle
                }
                Text(
                    subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            ModelStatusChip(
                isStreaming = isStreaming,
                tokensPerSecond = tokensPerSecond,
                modelReady = modelReady,
                activeModelName = activeModelName,
            )

            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Default.Search, null, tint = SaarthiColors.TextSecondary)
            }

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
}

// ── Empty State ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    language: SupportedLanguage,
    modifier: Modifier = Modifier,
    onSuggestionTap: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            MandalaCanvas(modifier = Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "सारथी",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp,
                    ),
                    color = SaarthiColors.Gold,
                )
                Text(
                    "SAARTHI",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 5.sp),
                    color = SaarthiColors.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            language.greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = SaarthiColors.Gold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            language.inputHint,
            style = MaterialTheme.typography.bodyMedium,
            color = SaarthiColors.TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureBadge("🔒", "Private")
            FeatureBadge("📴", "Offline")
            FeatureBadge("🇮🇳", "Bharat")
        }

        Spacer(Modifier.height(28.dp))

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
