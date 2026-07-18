package com.saarthi.core.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies MIGRATION_3_4 (rewrites shared_memory's primary key from (key)
 * to (sessionId, key) via create-new-table/copy/drop/rename — the exact
 * "silent corruption" risk class: a copy-step bug here would silently drop
 * or misattribute every user's saved memories on upgrade) and
 * MIGRATION_4_5 (pure schema-add) against a REAL SQLite engine, not a
 * hand-simulated one.
 *
 * Room's own MigrationTestHelper needs Robolectric or a device/emulator to
 * run (both require a real Android SQLite implementation) — neither is
 * available in this project. Since every Migration in this file is pure
 * db.execSQL(rawSql) with no other SupportSQLiteDatabase API used, a MockK
 * SupportSQLiteDatabase whose execSQL() forwards each call to a real,
 * pure-JVM SQLite engine (org.xerial:sqlite-jdbc) exercises the ACTUAL
 * production Migration objects — not a duplicate of their SQL — as an
 * ordinary JVM unit test, runnable in the exact same `testDebugUnitTest`
 * CI step every other test in this project already uses.
 *
 * The "before" schema for each test is copied verbatim from Room's own
 * exported schema JSON (schemas/.../3.json, 4.json — see the
 * room.schemaLocation config in this module's build.gradle.kts) rather
 * than hand-written from the entity classes, so there's no risk of testing
 * against a schema that's subtly different from what real installs
 * actually have on disk.
 */
class SaarthiDatabaseMigrationTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun tearDown() {
        connection.close()
    }

    /** Forwards every execSQL(sql) call straight to the real JDBC connection. */
    private fun fakeSupportDatabase(): SupportSQLiteDatabase {
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(any()) } answers {
            connection.createStatement().use { it.execute(firstArg<String>()) }
        }
        return db
    }

    // ── MIGRATION_3_4: shared_memory primary key rewrite ────────────────────

    @Test
    fun `MIGRATION_3_4 preserves every row, remapped into USER_SCOPE`() {
        seedV3Schema()
        connection.createStatement().use { st ->
            st.execute("INSERT INTO shared_memory (`key`, value, packSource, updatedAt) VALUES ('name', 'Arjun', 'USER', 1000)")
            st.execute("INSERT INTO shared_memory (`key`, value, packSource, updatedAt) VALUES ('city', 'Pune', 'USER', 2000)")
        }

        MIGRATION_3_4.migrate(fakeSupportDatabase())

        val rows = mutableListOf<Triple<String, String, String>>()
        connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT sessionId, `key`, value FROM shared_memory ORDER BY `key`")
            while (rs.next()) {
                rows.add(Triple(rs.getString("sessionId"), rs.getString("key"), rs.getString("value")))
            }
        }
        assertEquals(2, rows.size)
        assertEquals(Triple("__user_profile__", "city", "Pune"), rows[0])
        assertEquals(Triple("__user_profile__", "name", "Arjun"), rows[1])
    }

    @Test
    fun `MIGRATION_3_4 result enforces the new composite primary key, not the old single-column one`() {
        seedV3Schema()
        connection.createStatement().use { st ->
            st.execute("INSERT INTO shared_memory (`key`, value, packSource, updatedAt) VALUES ('name', 'Arjun', 'USER', 1000)")
        }

        MIGRATION_3_4.migrate(fakeSupportDatabase())

        // Under the OLD single-column PK, a second row with the same key
        // would violate the constraint. Under the NEW composite PK it must
        // succeed as long as sessionId differs — proving the migration
        // actually changed the constraint, not just the column list.
        connection.createStatement().use { st ->
            st.execute(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('chat-42', 'name', 'SomeoneElse', 'USER', 3000)",
            )
        }
        val count = connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) AS c FROM shared_memory WHERE `key`='name'")
            rs.next()
            rs.getInt("c")
        }
        assertEquals(2, count)
    }

    @Test
    fun `MIGRATION_3_4 does not touch unrelated tables`() {
        seedV3Schema()
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE conversation (`id` TEXT NOT NULL, `content` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `tokenCount` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            st.execute("INSERT INTO conversation VALUES ('msg-1', 'hello', 'user', 1000, 2, 'chat-1')")
        }

        MIGRATION_3_4.migrate(fakeSupportDatabase())

        val count = connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) AS c FROM conversation")
            rs.next()
            rs.getInt("c")
        }
        assertEquals("MIGRATION_3_4 must not affect any table besides shared_memory", 1, count)
    }

    @Test
    fun `MIGRATION_3_4 on an empty shared_memory table produces an empty result, not an error`() {
        seedV3Schema()

        MIGRATION_3_4.migrate(fakeSupportDatabase())

        val count = connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) AS c FROM shared_memory")
            rs.next()
            rs.getInt("c")
        }
        assertEquals(0, count)
    }

    // ── MIGRATION_4_5: rag_chunks schema-add ─────────────────────────────────

    @Test
    fun `MIGRATION_4_5 creates rag_chunks with both indices and a working schema`() {
        seedV4Schema()

        MIGRATION_4_5.migrate(fakeSupportDatabase())

        // Real INSERT against every declared column — a stronger check than
        // just querying sqlite_master, since a column-name typo in the
        // CREATE TABLE would fail here but not there.
        connection.createStatement().use { st ->
            st.execute(
                "INSERT INTO rag_chunks (sessionId, docUri, docName, mimeType, chunkIndex, text, createdAt) " +
                    "VALUES ('s1', 'content://doc', 'doc.pdf', 'application/pdf', 0, 'chunk text', 5000)",
            )
        }
        val indexNames = mutableListOf<String>()
        connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='rag_chunks'")
            while (rs.next()) indexNames.add(rs.getString("name"))
        }
        assertTrue(indexNames.contains("index_rag_chunks_sessionId"))
        assertTrue(indexNames.contains("index_rag_chunks_sessionId_docUri"))
    }

    @Test
    fun `MIGRATION_4_5 preserves existing shared_memory data untouched`() {
        seedV4Schema()
        connection.createStatement().use { st ->
            st.execute(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('chat-1', 'name', 'Arjun', 'USER', 1000)",
            )
        }

        MIGRATION_4_5.migrate(fakeSupportDatabase())

        val value = connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT value FROM shared_memory WHERE `key`='name'")
            rs.next()
            rs.getString("value")
        }
        assertEquals("Arjun", value)
    }

    // ── Schema fixtures, copied verbatim from Room's exported schema JSON ───

    /** schemas/com.saarthi.core.memory.db.SaarthiDatabase/3.json */
    private fun seedV3Schema() {
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE shared_memory (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "`packSource` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

    /** schemas/com.saarthi.core.memory.db.SaarthiDatabase/4.json */
    private fun seedV4Schema() {
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE shared_memory (`sessionId` TEXT NOT NULL, `key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "`packSource` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `key`))",
            )
        }
    }
}
