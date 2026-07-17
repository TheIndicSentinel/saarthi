package com.saarthi.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * StreamingSha256 exists specifically so a multi-GB model file can be hashed
 * without loading it into memory (PackVerifier.sha256Hex's ByteArray-based
 * approach would OOM on a 2.5-4.4GB model). These tests verify the chunked
 * MessageDigest.update() loop produces byte-identical results to the naive
 * MessageDigest.digest(fullArray) approach across buffer-size boundaries —
 * exactly where an off-by-one chunking bug would hide, and exactly the kind
 * of bug that would silently corrupt every model-integrity check in the app
 * without ever throwing an exception.
 *
 * Reference hashes are computed in-test via MessageDigest directly rather
 * than hardcoded constants, so a misremembered hash value can never make
 * this test wrong — the property under test is "streaming == non-streaming
 * for the same bytes", not "matches this specific literal string".
 */
class StreamingSha256Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun referenceHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `empty file matches digest of empty bytes`() {
        val file = tempFolder.newFile("empty.bin")
        assertEquals(referenceHash(ByteArray(0)), StreamingSha256.hex(file))
    }

    @Test
    fun `small file matches digest of the same bytes`() {
        val bytes = "the quick brown fox jumps over the lazy dog".toByteArray()
        val file = tempFolder.newFile("small.bin").also { it.writeBytes(bytes) }
        assertEquals(referenceHash(bytes), StreamingSha256.hex(file))
    }

    @Test
    fun `file exactly matching the default buffer size hashes correctly`() {
        // 512KB is StreamingSha256's DEFAULT_BUFFER_BYTES. A read-loop bug
        // that mishandles a read() returning exactly the buffer's full
        // capacity (as opposed to a partial read) would only surface here.
        val bytes = Random(seed = 1).nextBytes(512 * 1024)
        val file = tempFolder.newFile("exact-buffer.bin").also { it.writeBytes(bytes) }
        assertEquals(referenceHash(bytes), StreamingSha256.hex(file))
    }

    @Test
    fun `file spanning multiple uneven buffer reads hashes correctly`() {
        // 3.5x the default buffer size, deliberately not a clean multiple —
        // exercises several full chunks followed by one partial final chunk.
        val bytes = Random(seed = 2).nextBytes((512 * 1024 * 3.5).toInt())
        val file = tempFolder.newFile("multi-chunk.bin").also { it.writeBytes(bytes) }
        assertEquals(referenceHash(bytes), StreamingSha256.hex(file))
    }

    @Test
    fun `result is independent of the buffer size used`() {
        // Same content, three very different buffer sizes — the digest must
        // be identical regardless of how the file happens to be chunked.
        val bytes = Random(seed = 3).nextBytes(200 * 1024)
        val file = tempFolder.newFile("buffer-independence.bin").also { it.writeBytes(bytes) }
        val expected = referenceHash(bytes)
        assertEquals(expected, StreamingSha256.hex(file, bufferSizeBytes = 1024))
        assertEquals(expected, StreamingSha256.hex(file, bufferSizeBytes = 64 * 1024))
        assertEquals(expected, StreamingSha256.hex(file, bufferSizeBytes = 1024 * 1024))
    }

    @Test
    fun `hex output is lowercase`() {
        val bytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val file = tempFolder.newFile("case-check.bin").also { it.writeBytes(bytes) }
        val hex = StreamingSha256.hex(file)
        assertEquals(hex, hex.lowercase())
    }

    @Test
    fun `different content produces different hashes`() {
        // Sanity check against a hash function that accidentally ignores
        // its input (e.g. a buffer never actually read into the digest).
        val fileA = tempFolder.newFile("a.bin").also { it.writeBytes(Random(10).nextBytes(4096)) }
        val fileB = tempFolder.newFile("b.bin").also { it.writeBytes(Random(20).nextBytes(4096)) }
        assertNotEquals(StreamingSha256.hex(fileA), StreamingSha256.hex(fileB))
    }
}
