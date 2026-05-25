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
     * Every chunk across multiple sessions — used when a persona-packaged
     * knowledge bundle ([PackId] sentinel sessions like `global_pack_kisan`)
     * needs to be merged into the user's current chat corpus before BM25.
     * The composite (sessionId, docUri) index covers the WHERE clause.
     */
    @Query("SELECT * FROM rag_chunks WHERE sessionId IN (:sessionIds) ORDER BY sessionId ASC, id ASC")
    suspend fun getBySessions(sessionIds: List<String>): List<RagChunkEntity>

    /** Idempotency check — has this exact file already been indexed for this chat? */
    @Query("SELECT COUNT(*) FROM rag_chunks WHERE sessionId = :sessionId AND docUri = :docUri")
    suspend fun countByDoc(sessionId: String, docUri: String): Int

    /** Distinct documents indexed under a chat (for "what's attached here" listings). */
    @Query("SELECT DISTINCT docUri FROM rag_chunks WHERE sessionId = :sessionId")
    suspend fun listDocUris(sessionId: String): List<String>

    @Query("DELETE FROM rag_chunks WHERE sessionId = :sessionId AND docUri = :docUri")
    suspend fun deleteByDoc(sessionId: String, docUri: String)

    /** Used during session delete and clearHistory — cascades all RAG context. */
    @Query("DELETE FROM rag_chunks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
