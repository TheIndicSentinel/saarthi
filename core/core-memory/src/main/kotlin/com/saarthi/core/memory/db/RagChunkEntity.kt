package com.saarthi.core.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One overlapping text chunk extracted from a document attached to a
 * chat session. Persisted so RAG context survives process restart, app
 * swipe, and OS background GC — without this users had to re-attach the
 * file every time they reopened the app.
 *
 * The `docName` and `mimeType` are denormalised onto every chunk so the
 * prompt-side citation render never needs a second query. Lookups are
 * always session-scoped → composite index on (sessionId, docUri).
 */
@Entity(
    tableName = "rag_chunks",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "docUri"]),
    ],
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val docUri: String,
    val docName: String,
    val mimeType: String,
    val chunkIndex: Int,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)
