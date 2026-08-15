package com.saarthi.core.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation ORDER BY timestamp ASC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<ConversationEntity>

    /**
     * Last [limit] rows for the chat list / in-memory prompt window.
     * Full-session [getBySession] / [getAll] stay for export. Newest-first
     * inner select, then chronological for the UI.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM conversation
            WHERE sessionId = :sessionId
            ORDER BY timestamp DESC
            LIMIT :limit
        ) AS recent
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<ConversationEntity>

    companion object {
        /** Enough for the token-budgeted prompt; bounds RAM on huge threads. */
        const val UI_HISTORY_LIMIT = 100
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ConversationEntity>)

    @Query("DELETE FROM conversation")
    suspend fun deleteAll()

    @Query("DELETE FROM conversation WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteById(id: String)
}
