package com.saarthi.core.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ── Session-scoped queries (production path) ─────────────────────────
    //
    // Every chat sees ONLY its own memories. New chats start empty. Deleting
    // a chat cascades to its memories. This is the contract that prevents
    // "states in India" pulling in grocery-list context from another chat.

    @Query("SELECT * FROM shared_memory WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeBySession(sessionId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM shared_memory WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun getBySession(sessionId: String): List<MemoryEntity>

    @Query("SELECT * FROM shared_memory WHERE sessionId = :sessionId AND `key` = :key LIMIT 1")
    suspend fun getInSession(sessionId: String, key: String): MemoryEntity?

    @Query("DELETE FROM shared_memory WHERE sessionId = :sessionId AND `key` = :key")
    suspend fun deleteInSession(sessionId: String, key: String)

    @Query("DELETE FROM shared_memory WHERE sessionId = :sessionId")
    suspend fun deleteAllInSession(sessionId: String)

    // ── Cross-session (admin / settings paths only) ──────────────────────
    //
    // Do NOT use these in the chat hot-path — they break session isolation.
    // Reserved for "wipe all memories" in settings and similar admin actions.

    @Query("SELECT * FROM shared_memory ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("DELETE FROM shared_memory")
    suspend fun deleteEverything()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)
}
