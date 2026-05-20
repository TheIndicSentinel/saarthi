package com.saarthi.core.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryEntity::class, ConversationEntity::class, ChatSessionEntity::class],
    version = 4,   // v4: shared_memory got composite (sessionId, key) primary key for per-chat isolation.
    exportSchema = true,
)
abstract class SaarthiDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatSessionDao(): ChatSessionDao
}
