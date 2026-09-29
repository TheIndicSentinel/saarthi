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

    /**
     * The [limit] rows just before [beforeTimestamp] (inclusive — callers drop
     * ids they already hold, so equal-timestamp rows at a page edge aren't
     * skipped), chronological. Pages "Load earlier messages".
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM conversation
            WHERE sessionId = :sessionId AND timestamp <= :beforeTimestamp
            ORDER BY timestamp DESC
            LIMIT :limit
        ) AS older
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getOlderBySession(sessionId: String, beforeTimestamp: Long, limit: Int): List<ConversationEntity>

    /** Rows strictly older than [beforeTimestamp] — whether an earlier page exists. */
    @Query("SELECT COUNT(*) FROM conversation WHERE sessionId = :sessionId AND timestamp < :beforeTimestamp")
    suspend fun countOlderBySession(sessionId: String, beforeTimestamp: Long): Int

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
