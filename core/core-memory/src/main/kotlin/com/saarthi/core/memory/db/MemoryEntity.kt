package com.saarthi.core.memory.db

import androidx.room.Entity

/**
 * Per-chat user memory.
 *
 * Each row is scoped to a [sessionId] so memories created in one chat
 * cannot leak into another. This matches how ChatGPT / Claude / Gemini
 * scope memory: an "introduce yourself" question in chat A must not pull
 * in grocery-list memory the model emitted in chat B.
 *
 * Composite primary key (sessionId, key) so the same key can exist in
 * multiple chats independently — "name: Arjun" stored in chat A does not
 * collide with "name: Priya" stored in chat B.
 */
@Entity(
    tableName = "shared_memory",
    primaryKeys = ["sessionId", "key"],
)
data class MemoryEntity(
    val sessionId: String,
    val key: String,
    val value: String,
    val packSource: String,   // e.g. "MONEY", "KISAN", or "USER" when set by the chat itself
    val updatedAt: Long = System.currentTimeMillis(),
)
