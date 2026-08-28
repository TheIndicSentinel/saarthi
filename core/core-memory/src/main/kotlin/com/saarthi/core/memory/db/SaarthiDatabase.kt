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
    // v6: rag_chunks_fts FTS5 virtual table (external content) for large
    //     sessions — BM25 prefilter only when the measurement gate fires.
    // v7: rag_chunks metadata columns (chapterId, section, headingPath, page, role)
    //     for index-time structure registry (Wave 2).
    // v8: parentChunkIndex for hierarchical section graph (Wave 6).
    version = 8,
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

/**
 * v5 → v6: FTS5 external-content index on rag_chunks.text for session-scoped
 * lexical prefilter before BM25 on huge/slow corpora. Triggers keep the FTS
 * table in sync; existing rows are backfilled on upgrade.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS rag_chunks_fts USING fts5(
                text,
                sessionId UNINDEXED,
                content='rag_chunks',
                content_rowid='id'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_ai AFTER INSERT ON rag_chunks BEGIN
                INSERT INTO rag_chunks_fts(rowid, text, sessionId)
                VALUES (new.id, new.text, new.sessionId);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_ad AFTER DELETE ON rag_chunks BEGIN
                INSERT INTO rag_chunks_fts(rag_chunks_fts, rowid, text, sessionId)
                VALUES ('delete', old.id, old.text, old.sessionId);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_au AFTER UPDATE ON rag_chunks BEGIN
                INSERT INTO rag_chunks_fts(rag_chunks_fts, rowid, text, sessionId)
                VALUES ('delete', old.id, old.text, old.sessionId);
                INSERT INTO rag_chunks_fts(rowid, text, sessionId)
                VALUES (new.id, new.text, new.sessionId);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO rag_chunks_fts(rowid, text, sessionId)
            SELECT id, text, sessionId FROM rag_chunks
            """.trimIndent(),
        )
    }
}

/**
 * v6 → v7: optional structure metadata on rag_chunks for index-time chapter
 * registry. Nullable columns — existing rows stay valid until re-indexed.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN chapterId TEXT")
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN sectionNum TEXT")
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN headingPath TEXT")
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN pageNum INTEGER")
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN chunkRole TEXT")
    }
}

/**
 * v7 → v8: parentChunkIndex links multi-chunk legal sections for complete-section fetch.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rag_chunks ADD COLUMN parentChunkIndex INTEGER")
    }
}
