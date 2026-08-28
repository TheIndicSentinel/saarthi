package com.saarthi.core.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqliteFileFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missing_or_short_file_is_not_unencrypted_sqlite() {
        assertFalse(SqliteFileFormat.isUnencryptedSqlite(File(tmp.root, "nope.db")))
        val short = tmp.newFile("short.db")
        short.writeBytes("SQLite".toByteArray())
        assertFalse(SqliteFileFormat.isUnencryptedSqlite(short))
    }

    @Test
    fun sqlite_header_is_detected() {
        val file = tmp.newFile("plain.db")
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        file.writeBytes(header + ByteArray(48) { 0 })
        assertTrue(SqliteFileFormat.isUnencryptedSqlite(file))
    }

    @Test
    fun random_header_is_not_treated_as_plaintext() {
        val file = tmp.newFile("cipher.db")
        file.writeBytes(ByteArray(64) { (it * 17).toByte() })
        assertFalse(SqliteFileFormat.isUnencryptedSqlite(file))
    }

    @Test
    fun hex_round_trip() {
        val raw = byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte())
        assertEquals("000f10ff", SqliteFileFormat.toHex(raw))
        assertTrue(raw.contentEquals(SqliteFileFormat.fromHex("000f10ff")))
        assertTrue(raw.contentEquals(SqliteFileFormat.fromHex("000F10FF")))
    }

    @Test
    fun attach_sql_uses_bound_path_and_key() {
        assertEquals(
            "ATTACH DATABASE ? AS encrypted KEY ?;",
            PlaintextToSqlCipherMigrator.ATTACH_ENCRYPTED,
        )
    }
}
