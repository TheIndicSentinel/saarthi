package com.saarthi.feature.assistant.domain

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: MessageRole,
    val attachments: List<AttachedFile> = emptyList(),
    val isStreaming: Boolean = false,
    val tokenCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class MessageRole { USER, ASSISTANT }
