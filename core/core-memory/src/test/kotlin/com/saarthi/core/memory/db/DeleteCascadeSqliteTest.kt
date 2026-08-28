package com.saarthi.core.memory.db

import com.saarthi.core.memory.domain.MemoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * JVM integration test for the SQL contract behind
 * [com.saarthi.feature.assistant.data.ChatRepositoryImpl.deleteSession] and
 * [com.saarthi.feature.assistant.data.ChatRepositoryImpl.deleteAllData].
 *
 * Feature-module unit tests MockK [DatabaseTransactionRunner] and never open
 * SQLite, so they cannot catch a half-wipe (messages gone, RAG chunks still
 * present) or prove ROLLBACK restores every row. This suite runs the same
 * DELETE predicates the DAOs use against the real sqlite-jdbc engine — the
 * same approach as [SaarthiDatabaseMigrationTest] — without Room, Hilt, or a
 * device.
 *
 * Schema is copied from Room's exported
 * `schemas/com.saarthi.core.memory.db.SaarthiDatabase/8.json`. That JSON has
 * no `rag_chunks_fts` entity; FTS is created only by [MIGRATION_5_6] and is
 * omitted here so a missing virtual-table/trigger cannot make DELETE fail.
 * Columns on the four cascade tables match the v8 entities.
 */
class DeleteCascadeSqliteTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        seedV8Schema(connection)
    }

    @After
    fun tearDown() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
    }

    // ── 1. Happy cascade ───────────────────────────────────────────────────

    @Test
    fun `deleteSession cascade removes only that session's artefacts and leaves others plus USER_SCOPE`() {
        seedTwoSessionsAndUserProfile(connection)

        inTransaction(connection) {
            deleteSessionCascade(connection, "s1")
        }

        assertEquals(0, count(connection, "conversation", "sessionId = 's1'"))
        assertEquals(0, count(connection, "shared_memory", "sessionId = 's1'"))
        assertEquals(0, count(connection, "rag_chunks", "sessionId = 's1'"))
        assertEquals(0, count(connection, "chat_sessions", "id = 's1'"))

        assertEquals(2, count(connection, "conversation", "sessionId = 's2'"))
        assertEquals(1, count(connection, "shared_memory", "sessionId = 's2'"))
        assertEquals(1, count(connection, "rag_chunks", "sessionId = 's2'"))
        assertEquals(1, count(connection, "chat_sessions", "id = 's2'"))

        assertEquals(
            1,
            count(connection, "shared_memory", "sessionId = '${MemoryRepository.USER_SCOPE}'"),
        )
        assertEquals("Arjun", scalar(connection, "SELECT value FROM shared_memory WHERE sessionId = '${MemoryRepository.USER_SCOPE}' AND `key` = 'name'"))
        assertEquals("s2 farm notes", scalar(connection, "SELECT value FROM shared_memory WHERE sessionId = 's2' AND `key` = 'note'"))
    }

    // ── 2. Atomic rollback ─────────────────────────────────────────────────

    @Test
    fun `deleteSession cascade rolls back atomically so a mid-wipe failure leaves no half-deleted rows`() {
        seedTwoSessionsAndUserProfile(connection)
        val before = snapshotCounts(connection)

        connection.autoCommit = false
        try {
            deleteSessionCascade(connection, "s1")
            // Force a statement failure before COMMIT. SQLite RAISE() is
            // trigger-only on some builds; either an ABORT or a syntax error
            // is enough — the DELETEs above must not survive ROLLBACK.
            try {
                connection.createStatement().use { it.execute("SELECT RAISE(ABORT, 'boom')") }
                fail("expected RAISE/ABORT to fail the statement")
            } catch (_: SQLException) {
                // statement aborted; transaction still open
            }
            connection.rollback()
        } finally {
            connection.autoCommit = true
        }

        assertEquals("rollback must restore every s1 conversation row", before.s1Conversations, count(connection, "conversation", "sessionId = 's1'"))
        assertEquals("rollback must restore every s1 memory row", before.s1Memories, count(connection, "shared_memory", "sessionId = 's1'"))
        assertEquals("rollback must restore every s1 rag_chunks row", before.s1RagChunks, count(connection, "rag_chunks", "sessionId = 's1'"))
        assertEquals("rollback must restore the s1 session row", before.s1Sessions, count(connection, "chat_sessions", "id = 's1'"))
        assertEquals(before.s2Conversations, count(connection, "conversation", "sessionId = 's2'"))
        assertEquals(before.s2Memories, count(connection, "shared_memory", "sessionId = 's2'"))
        assertEquals(before.s2RagChunks, count(connection, "rag_chunks", "sessionId = 's2'"))
        assertEquals(before.s2Sessions, count(connection, "chat_sessions", "id = 's2'"))
        assertEquals(before.userScopeMemories, count(connection, "shared_memory", "sessionId = '${MemoryRepository.USER_SCOPE}'"))
        assertEquals("hello s1", scalar(connection, "SELECT content FROM conversation WHERE id = 'm1-user'"))
        assertEquals("s1 grocery list", scalar(connection, "SELECT value FROM shared_memory WHERE sessionId = 's1' AND `key` = 'note'"))
        assertEquals("s1 chunk", scalar(connection, "SELECT text FROM rag_chunks WHERE sessionId = 's1'"))
        assertEquals("Chat 1", scalar(connection, "SELECT title FROM chat_sessions WHERE id = 's1'"))
    }

    // ── 3. delete-all + USER_SCOPE ─────────────────────────────────────────

    @Test
    fun `deleteAllData cascade wipes every session artefact and USER_SCOPE in one transaction`() {
        seedTwoSessionsAndUserProfile(connection)

        inTransaction(connection) {
            deleteAllDataCascade(connection, listOf("s1", "s2"))
        }

        assertEquals(0, count(connection, "conversation"))
        assertEquals(0, count(connection, "shared_memory"))
        assertEquals(0, count(connection, "rag_chunks"))
        assertEquals(0, count(connection, "chat_sessions"))
        assertEquals(
            0,
            count(connection, "shared_memory", "sessionId = '${MemoryRepository.USER_SCOPE}'"),
        )
    }

    // ── 4. VACUUM reclaim ──────────────────────────────────────────────────

    @Test
    fun `VACUUM after committed bulk delete reclaims unused pages on a file-backed database`() {
        val dbFile = File.createTempFile("saarthi-delete-cascade-vacuum", ".db")
        var fileConnection: Connection? = null
        try {
            fileConnection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            fileConnection.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = DELETE")
            }
            seedV8Schema(fileConnection)

            val payload = "x".repeat(PAYLOAD_BYTES)
            fileConnection.prepareStatement(
                "INSERT INTO rag_chunks (sessionId, docUri, docName, mimeType, chunkIndex, text, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, "s1")
                ps.setString(2, "content://large")
                ps.setString(3, "large.txt")
                ps.setString(4, "text/plain")
                ps.setInt(5, 0)
                ps.setString(6, payload)
                ps.setLong(7, 1L)
                ps.executeUpdate()
            }

            val sizeAfterInsert = dbFile.length()
            assertTrue(
                "seed payload must actually grow the file (was $sizeAfterInsert bytes)",
                sizeAfterInsert >= PAYLOAD_BYTES.toLong(),
            )

            inTransaction(fileConnection) {
                deleteSessionCascade(fileConnection, "s1")
            }
            val sizeAfterDelete = dbFile.length()

            fileConnection.createStatement().use { it.execute("VACUUM") }
            val sizeAfterVacuum = dbFile.length()

            // SQLite DELETE only marks pages free; file size usually stays put
            // until VACUUM. Allow a small leftover (empty schema + page slack).
            assertTrue(
                "VACUUM should reclaim the deleted payload: " +
                    "afterInsert=$sizeAfterInsert afterDelete=$sizeAfterDelete afterVacuum=$sizeAfterVacuum",
                sizeAfterVacuum < sizeAfterInsert - VACUUM_RECLAIM_MIN_BYTES,
            )
            assertTrue(
                "VACUUM should shrink vs the post-delete freelist file: " +
                    "afterDelete=$sizeAfterDelete afterVacuum=$sizeAfterVacuum",
                sizeAfterVacuum < sizeAfterDelete - VACUUM_RECLAIM_MIN_BYTES,
            )
        } finally {
            fileConnection?.close()
            dbFile.delete()
            File(dbFile.path + "-journal").delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    // ── SQL contract (mirrors DAO @Query strings) ──────────────────────────

    /**
     * Same order as [com.saarthi.feature.assistant.data.ChatRepositoryImpl.deleteSession]:
     * conversations → session memories → rag_chunks → chat_sessions row.
     */
    private fun deleteSessionCascade(conn: Connection, sessionId: String) {
        conn.prepareStatement("DELETE FROM conversation WHERE sessionId = ?").use {
            it.setString(1, sessionId)
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM shared_memory WHERE sessionId = ?").use {
            it.setString(1, sessionId)
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM rag_chunks WHERE sessionId = ?").use {
            it.setString(1, sessionId)
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM chat_sessions WHERE id = ?").use {
            it.setString(1, sessionId)
            it.executeUpdate()
        }
    }

    /**
     * Same order as [com.saarthi.feature.assistant.data.ChatRepositoryImpl.deleteAllData]:
     * per-session conversation/memory/RAG wipes, then USER_SCOPE memories, then
     * session rows.
     */
    private fun deleteAllDataCascade(conn: Connection, sessionIds: List<String>) {
        for (id in sessionIds) {
            conn.prepareStatement("DELETE FROM conversation WHERE sessionId = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM shared_memory WHERE sessionId = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM rag_chunks WHERE sessionId = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
        }
        conn.prepareStatement("DELETE FROM shared_memory WHERE sessionId = ?").use {
            it.setString(1, MemoryRepository.USER_SCOPE)
            it.executeUpdate()
        }
        for (id in sessionIds) {
            conn.prepareStatement("DELETE FROM chat_sessions WHERE id = ?").use {
                it.setString(1, id)
                it.executeUpdate()
            }
        }
    }

    private fun inTransaction(conn: Connection, block: () -> Unit) {
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            block()
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = previous
        }
    }

    // ── Schema fixtures, copied from Room exported 8.json ──────────────────

    /**
     * CREATE TABLE / INDEX SQL from
     * `core/core-memory/schemas/com.saarthi.core.memory.db.SaarthiDatabase/8.json`
     * with `${TABLE_NAME}` substituted. FTS is not in that export.
     */
    private fun seedV8Schema(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS `shared_memory` (`sessionId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, `packSource` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`, `key`))",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `tokenCount` INTEGER NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS `rag_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, `docUri` TEXT NOT NULL, `docName` TEXT NOT NULL, " +
                    "`mimeType` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `chapterId` TEXT, `sectionNum` TEXT, `headingPath` TEXT, " +
                    "`pageNum` INTEGER, `chunkRole` TEXT, `parentChunkIndex` INTEGER)",
            )
            st.execute(
                "CREATE INDEX IF NOT EXISTS `index_rag_chunks_sessionId` ON `rag_chunks` (`sessionId`)",
            )
            st.execute(
                "CREATE INDEX IF NOT EXISTS `index_rag_chunks_sessionId_docUri` ON `rag_chunks` (`sessionId`, `docUri`)",
            )
        }
    }

    private fun seedTwoSessionsAndUserProfile(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO chat_sessions (id, title, createdAt, updatedAt) VALUES ('s1', 'Chat 1', 1000, 1000)")
            st.execute("INSERT INTO chat_sessions (id, title, createdAt, updatedAt) VALUES ('s2', 'Chat 2', 2000, 2000)")

            st.execute(
                "INSERT INTO conversation (id, content, role, timestamp, tokenCount, sessionId) " +
                    "VALUES ('m1-user', 'hello s1', 'user', 1001, 2, 's1')",
            )
            st.execute(
                "INSERT INTO conversation (id, content, role, timestamp, tokenCount, sessionId) " +
                    "VALUES ('m1-asst', 'hi from s1', 'assistant', 1002, 3, 's1')",
            )
            st.execute(
                "INSERT INTO conversation (id, content, role, timestamp, tokenCount, sessionId) " +
                    "VALUES ('m2-user', 'hello s2', 'user', 2001, 2, 's2')",
            )
            st.execute(
                "INSERT INTO conversation (id, content, role, timestamp, tokenCount, sessionId) " +
                    "VALUES ('m2-asst', 'hi from s2', 'assistant', 2002, 3, 's2')",
            )

            st.execute(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('s1', 'note', 's1 grocery list', 'USER', 1003)",
            )
            st.execute(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('s2', 'note', 's2 farm notes', 'USER', 2003)",
            )
            st.execute(
                "INSERT INTO shared_memory (sessionId, `key`, value, packSource, updatedAt) " +
                    "VALUES ('${MemoryRepository.USER_SCOPE}', 'name', 'Arjun', 'USER', 500)",
            )

            st.execute(
                "INSERT INTO rag_chunks (sessionId, docUri, docName, mimeType, chunkIndex, text, createdAt) " +
                    "VALUES ('s1', 'content://s1', 's1.pdf', 'application/pdf', 0, 's1 chunk', 1004)",
            )
            st.execute(
                "INSERT INTO rag_chunks (sessionId, docUri, docName, mimeType, chunkIndex, text, createdAt) " +
                    "VALUES ('s2', 'content://s2', 's2.pdf', 'application/pdf', 0, 's2 chunk', 2004)",
            )
        }
    }

    private fun count(conn: Connection, table: String, where: String? = null): Int {
        val sql = if (where == null) {
            "SELECT COUNT(*) AS c FROM $table"
        } else {
            "SELECT COUNT(*) AS c FROM $table WHERE $where"
        }
        return conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            rs.next()
            rs.getInt("c")
        }
    }

    private fun scalar(conn: Connection, sql: String): String =
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            assertTrue("expected a row for: $sql", rs.next())
            rs.getString(1)
        }

    private fun snapshotCounts(conn: Connection) = CascadeCounts(
        s1Conversations = count(conn, "conversation", "sessionId = 's1'"),
        s1Memories = count(conn, "shared_memory", "sessionId = 's1'"),
        s1RagChunks = count(conn, "rag_chunks", "sessionId = 's1'"),
        s1Sessions = count(conn, "chat_sessions", "id = 's1'"),
        s2Conversations = count(conn, "conversation", "sessionId = 's2'"),
        s2Memories = count(conn, "shared_memory", "sessionId = 's2'"),
        s2RagChunks = count(conn, "rag_chunks", "sessionId = 's2'"),
        s2Sessions = count(conn, "chat_sessions", "id = 's2'"),
        userScopeMemories = count(conn, "shared_memory", "sessionId = '${MemoryRepository.USER_SCOPE}'"),
    )

    private data class CascadeCounts(
        val s1Conversations: Int,
        val s1Memories: Int,
        val s1RagChunks: Int,
        val s1Sessions: Int,
        val s2Conversations: Int,
        val s2Memories: Int,
        val s2RagChunks: Int,
        val s2Sessions: Int,
        val userScopeMemories: Int,
    )

    private companion object {
        const val PAYLOAD_BYTES = 1_048_576
        const val VACUUM_RECLAIM_MIN_BYTES = 400_000L
    }
}
