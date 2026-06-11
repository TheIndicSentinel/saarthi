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
 * v3 → v4: `shared_memory` gained a `sessionId` column and its primary key
 * changed from `(key)` to `(sessionId, key)` — memory became per-chat instead
 * of global. SQLite can't alter a primary key in place, so we recreate the
 * table and copy existing rows. v3 memories were global (no session), so they
 * are migrated into the USER_SCOPE bucket ("__user_profile__") — the durable
 * cross-chat profile tier — which keeps them visible in every chat exactly as
 * before. `conversation` and `chat_sessions` were unchanged in this version,
 * so chat history and sessions are preserved untouched.
 *
 * Without this migration a v3 install upgrading would fall through to the
 * destructive fallback and lose ALL chat history, sessions, and memories.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shared_memory_new (
                sessionId TEXT NOT NULL,
                `key` TEXT NOT NULL,
                value TEXT NOT NULL,
                packSource TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(sessionId, `key`)
            )
            """.trimIndent()
        )
        // Existing global memories → USER_SCOPE so they stay visible everywhere.
        db.execSQL(
            """
            INSERT OR IGNORE INTO shared_memory_new (sessionId, `key`, value, packSource, updatedAt)
            SELECT '__user_profile__', `key`, value, packSource, updatedAt FROM shared_memory
            """.trimIndent()
        )
        db.execSQL("DROP TABLE shared_memory")
        db.execSQL("ALTER TABLE shared_memory_new RENAME TO shared_memory")
    }
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
