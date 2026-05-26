package com.saarthi.feature.assistant.ui.pack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.ui.components.MessageBubble
import com.saarthi.feature.assistant.viewmodel.PackChatViewModel

/**
 * Dedicated chat surface for the Kisan knowledge pack — completely
 * separate from the main AssistantScreen. Backed by [PackChatViewModel]
 * which keeps its own in-memory message list and never touches chat
 * sessions, persona preferences, or ChatRepository.
 *
 * Reuses the shared [MessageBubble] composable so pack answers get the
 * same markdown / code-block / citation rendering as the main chat,
 * without any shared state.
 */
@Composable
fun PackChatScreen(
    onBack: () -> Unit,
    viewModel: PackChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val speakingId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Keep the latest message in view as tokens stream in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.Bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SaarthiColors.Text2)
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "🌾  Kisan Saathi",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = SaarthiColors.Text, fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    language.kisanChatSubtitle,
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3),
                )
            }
            // Clear / start-fresh — the "manage conversation" action. The
            // chat is otherwise persisted across navigation, so this is the
            // only way to wipe it.
            if (messages.isNotEmpty() && !isGenerating) {
                IconButton(onClick = { viewModel.clear() }) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Clear conversation",
                        tint = SaarthiColors.Text3,
                    )
                }
            }
        }

        // ── Messages / empty state ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    title = language.kisanAskTitle,
                    subtitle = language.kisanEmptySubtitle,
                    starters = language.kisanStarters,
                    onPick = { q -> if (!isGenerating) viewModel.ask(q) },
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onDelete = {},
                            onRetry = { viewModel.retry(msg.id) },
                            onListen = { viewModel.toggleSpeak(msg.id, msg.content) },
                            isSpeaking = speakingId == msg.id,
                            avatarLabel = "🌾",
                        )
                    }
                }
            }
        }

        // ── Input bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(language.inputHint, color = SaarthiColors.Text3) },
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank() && !isGenerating) { viewModel.ask(input); input = "" }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SaarthiColors.Surface,
                    unfocusedContainerColor = SaarthiColors.Surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedTextColor = SaarthiColors.Text,
                    unfocusedTextColor = SaarthiColors.Text,
                    cursorColor = SaarthiColors.Marigold,
                ),
                // Pin the glyph colour to the theme's text token. Without an
                // explicit textStyle colour the field inherits an unspecified
                // default that renders near-white in light mode — invisible
                // on the white input surface. Matches ChatInputBar.
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (input.isBlank() || isGenerating) SaarthiColors.Surface else SaarthiColors.Marigold)
                    .clickable(enabled = input.isNotBlank() && !isGenerating) {
                        viewModel.ask(input); input = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (input.isBlank() || isGenerating) SaarthiColors.Text3 else SaarthiColors.Bg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    starters: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🌾", style = MaterialTheme.typography.displayLarge.copy(fontSize = 52.sp))
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = SaarthiColors.Text, fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2, lineHeight = 19.sp),
        )
        Spacer(Modifier.height(18.dp))
        starters.forEach { q ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SaarthiColors.Surface)
                    .border(1.dp, SaarthiColors.Border, RoundedCornerShape(12.dp))
                    .clickable { onPick(q) }
                    .padding(14.dp),
            ) {
                Text(q, style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text))
            }
        }
    }
}
