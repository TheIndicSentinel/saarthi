package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.ChatSessionEntity
import com.saarthi.feature.assistant.domain.ChatSession

/** Room row → session chip. Field-for-field copy. */
internal fun ChatSessionEntity.toSession() = ChatSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
