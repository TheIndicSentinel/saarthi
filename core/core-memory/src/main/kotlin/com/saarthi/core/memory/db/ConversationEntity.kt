package com.saarthi.core.memory.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val content: String,
    val role: String,           // "USER" | "ASSISTANT"
    val timestamp: Long,
    val tokenCount: Int = 0,
    val sessionId: String = "default",
)
