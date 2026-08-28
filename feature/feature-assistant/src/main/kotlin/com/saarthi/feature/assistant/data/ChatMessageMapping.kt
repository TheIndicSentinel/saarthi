package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.ConversationEntity
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole

/**
 * Room mapping for chat bubbles. Identical in main chat and pack chat for
 * [ChatMessage.toEntity]; [ConversationEntity.toChatMessage] is the main-chat
 * path ([MessageRole.valueOf]). Pack chat keeps a slightly more defensive
 * role parse of its own.
 */
internal fun ChatMessage.toEntity(sessionId: String) = ConversationEntity(
    id = id,
    content = content,
    role = role.name,
    timestamp = timestamp,
    tokenCount = tokenCount,
    sessionId = sessionId,
)

internal fun ConversationEntity.toChatMessage() = ChatMessage(
    id = id,
    content = content,
    role = MessageRole.valueOf(role),
    timestamp = timestamp,
    tokenCount = tokenCount,
    isStreaming = false,
)
