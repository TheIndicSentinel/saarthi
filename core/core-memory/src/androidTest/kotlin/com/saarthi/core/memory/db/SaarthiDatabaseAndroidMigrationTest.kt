package com.saarthi.core.memory.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saarthi.core.memory.domain.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production [MIGRATION_3_4] / [MIGRATION_4_5] against Android SQLite and
 * Room's exported schema JSON. Does not launch the app or touch RAG/chat
 * code — only opens a throwaway test database.
 */
@RunWith(AndroidJUnit4::class)
class SaarthiDatabaseAndroidMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SaarthiDatabase::class.java,
    )

    @Test
    fun migrate3To4_memoriesLandInUserScope_conversationsUntouched() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO shared_memory (`key`, value, packSource, updatedAt) " +
                    "VALUES ('name', 'Arjun', 'USER', 1000)",
            )
            execSQL(
                "INSERT INTO conversation (id, content, role, timestamp, tokenCount, sessionId) " +
                    "VALUES ('msg-1', 'hello', 'user', 1000, 2, 'chat-1')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT sessionId, `key`, value FROM shared_memory").use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(MemoryRepository.USER_SCOPE, c.getString(0))
                assertEquals("name", c.getString(1))
                assertEquals("Arjun", c.getString(2))
            }
            db.query("SELECT COUNT(*) FROM conversation").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate4To5_addsRagChunks_andPreservesMemory() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('chat-1', 'name', 'Arjun', 'USER', 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            db.query("SELECT value FROM shared_memory WHERE `key`='name'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Arjun", c.getString(0))
            }
            db.execSQL(
                "INSERT INTO rag_chunks (sessionId, docUri, docName, mimeType, chunkIndex, text, createdAt) " +
                    "VALUES ('s1', 'content://doc', 'doc.pdf', 'application/pdf', 0, 'chunk text', 5000)",
            )
            val indexNames = mutableListOf<String>()
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='rag_chunks'",
            ).use { c ->
                while (c.moveToNext()) indexNames.add(c.getString(0))
            }
            assertTrue(indexNames.contains("index_rag_chunks_sessionId"))
            assertTrue(indexNames.contains("index_rag_chunks_sessionId_docUri"))
        }
    }

    @Test
    fun migrate3To5_oldInstallPath_preservesMemoryAndAddsRagChunks() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO shared_memory (`key`, value, packSource, updatedAt) " +
                    "VALUES ('city', 'Pune', 'USER', 2000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MIGRATION_3_4,
            MIGRATION_4_5,
        ).use { db ->
            db.query("SELECT sessionId, value FROM shared_memory WHERE `key`='city'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(MemoryRepository.USER_SCOPE, c.getString(0))
                assertEquals("Pune", c.getString(1))
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='rag_chunks'",
            ).use { c ->
                assertTrue(c.moveToFirst())
            }
        }
    }

    private companion object {
        const val TEST_DB = "saarthi-migration-test.db"
    }
}
