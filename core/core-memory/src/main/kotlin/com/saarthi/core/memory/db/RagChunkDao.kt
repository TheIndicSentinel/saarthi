package com.saarthi.core.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RagChunkDao {

    /** Bulk-insert chunks for a single document. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    /** Every chunk for a session, ordered by insertion (id ASC). */
    @Query("SELECT * FROM rag_chunks WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getBySession(sessionId: String): List<RagChunkEntity>

    /**
     * Every chunk across multiple sessions — optional hook for callers that
     * need a multi-session corpus in one query. Not used on the main assistant
     * and are retrieved only from the dedicated pack chat surface, not merged
     * into the user's chat session before BM25.
     * The composite (sessionId, docUri) index covers the WHERE clause.
     */
    @Query("SELECT * FROM rag_chunks WHERE sessionId IN (:sessionIds) ORDER BY sessionId ASC, id ASC")
    suspend fun getBySessions(sessionIds: List<String>): List<RagChunkEntity>

    /** Idempotency check — has this exact file already been indexed for this chat? */
    @Query("SELECT COUNT(*) FROM rag_chunks WHERE sessionId = :sessionId AND docUri = :docUri")
    suspend fun countByDoc(sessionId: String, docUri: String): Int

    @Query("SELECT * FROM rag_chunks WHERE sessionId = :sessionId AND docUri = :docUri")
    suspend fun getByDoc(sessionId: String, docUri: String): List<RagChunkEntity>

    /** Distinct documents indexed under a chat (for "what's attached here" listings). */
    @Query("SELECT DISTINCT docUri FROM rag_chunks WHERE sessionId = :sessionId")
    suspend fun listDocUris(sessionId: String): List<String>

    @Query("DELETE FROM rag_chunks WHERE sessionId = :sessionId AND docUri = :docUri")
    suspend fun deleteByDoc(sessionId: String, docUri: String)

    /** Used during session delete and clearHistory — cascades all RAG context. */
    @Query("DELETE FROM rag_chunks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
