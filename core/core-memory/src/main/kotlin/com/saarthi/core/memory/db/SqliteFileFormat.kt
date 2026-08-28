package com.saarthi.core.memory.db

import java.io.File

/**
 * Detects a standard (unencrypted) SQLite main file vs a SQLCipher file.
 *
 * Plain SQLite starts with the 16-byte header `SQLite format 3\u0000`.
 * SQLCipher 4 encrypts the header, so those bytes look random.
 */
object SqliteFileFormat {
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isUnencryptedSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size.toLong()) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { stream ->
            val read = stream.read(header)
            if (read < SQLITE_HEADER.size) return false
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    /** Lowercase hex; empty [bytes] → empty string. */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even, was ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hexDigit(hex[i])
            val lo = hexDigit(hex[i + 1])
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun hexDigit(c: Char): Int {
        val v = Character.digit(c, 16)
        require(v >= 0) { "invalid hex digit: $c" }
        return v
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
