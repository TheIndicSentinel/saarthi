package com.saarthi.core.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        ConversationEntity::class,
        ChatSessionEntity::class,
        RagChunkEntity::class,
    ],
    // v5: rag_chunks table — persisted document chunks for the production
    //     BM25 RAG path. Replaces the in-memory session-docs map that
    //     dropped extracted text on every process restart.
    version = 5,
    exportSchema = true,
)
abstract class SaarthiDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun ragChunkDao(): RagChunkDao
}

/**
 * v4 → v5: add `rag_chunks` table with its two lookup indices. No data
 * migration needed (the previous in-memory implementation persisted
 * nothing) so this is purely a schema-add — existing chat history,
 * sessions, and memories are preserved across the upgrade.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rag_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                docUri TEXT NOT NULL,
                docName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_sessionId ON rag_chunks(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_sessionId_docUri ON rag_chunks(sessionId, docUri)")
    }
}
