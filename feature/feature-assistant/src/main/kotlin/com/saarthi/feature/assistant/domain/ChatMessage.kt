package com.saarthi.feature.assistant.domain

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * Marked @Immutable so Compose can skip recomposing every sibling
 * MessageBubble when one bubble streams in new content. Without this,
 * `List<AttachedFile>` defaults to UNSTABLE (List<T> isn't intrinsically
 * stable in Compose), forcing the whole LazyColumn item slot to recompose
 * every token tick — measurable jank during long replies.
 */
@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: MessageRole,
    val attachments: List<AttachedFile> = emptyList(),
    val isStreaming: Boolean = false,
    val tokenCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * True for an assistant row that is a UI notice, not a model reply —
     * error / empty / stopped / not-ready text. Shown in the bubble but never
     * persisted and never fed back into later prompts' recap.
     */
    val isPlaceholder: Boolean = false,
)

enum class MessageRole { USER, ASSISTANT }
