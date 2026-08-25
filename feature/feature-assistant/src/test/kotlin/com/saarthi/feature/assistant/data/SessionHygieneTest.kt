package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHygieneTest {

    private fun usage(uri: String, chars: Int, at: Long) =
        SessionDocUsage(uri = uri, contentChars = chars, firstIndexedAt = at)

    @Test
    fun `under the session cap evicts nothing`() {
        val existing = listOf(usage("a", 1_000, 1), usage("b", 1_000, 2))
        assertTrue(urisToEvictForSessionCap(existing, "c", 1_000, maxDocs = 12, maxChars = 10_000).isEmpty())
    }

    @Test
    fun `over doc cap evicts oldest first and never the incoming uri`() {
        val existing = listOf(
            usage("old", 100, 1),
            usage("mid", 100, 2),
            usage("incoming", 50, 3),
        )
        val evict = urisToEvictForSessionCap(
            existing, incomingUri = "incoming", incomingChars = 80,
            maxDocs = 2, maxChars = 10_000,
        )
        assertEquals(listOf("old"), evict)
    }

    @Test
    fun `over char cap keeps dropping oldest until the incoming file fits`() {
        val existing = listOf(
            usage("a", 800, 1),
            usage("b", 800, 2),
        )
        val evict = urisToEvictForSessionCap(
            existing, incomingUri = "c", incomingChars = 500,
            maxDocs = 12, maxChars = 1_000,
        )
        assertEquals(listOf("a", "b"), evict)
    }

    @Test
    fun `token cache evicts other sessions and keeps the active one`() {
        val counts = mapOf("idle" to 500, "active" to 400, "old" to 200)
        val evict = sessionsToEvictForTokenCache(
            counts, keepSession = "active", currentSize = 1_100, maxChunks = 800,
        )
        assertTrue(evict.isNotEmpty())
        assertTrue("active" !in evict)
        assertTrue(evict.contains("idle") || evict.contains("old"))
    }

    @Test
    fun `token cache under the cap evicts nothing`() {
        assertTrue(
            sessionsToEvictForTokenCache(
                mapOf("a" to 10, "b" to 10), keepSession = "a", currentSize = 20, maxChunks = 800,
            ).isEmpty(),
        )
    }

    @Test
    fun `same stamp on another uri is an alias`() {
        val stamps = listOf("content://old" to "100:50", "content://other" to "9:9")
        assertEquals("content://old", existingUriWithStamp(stamps, "content://new", "100:50"))
        assertNull(existingUriWithStamp(stamps, "content://old", "100:50"))
        assertNull(existingUriWithStamp(stamps, "content://new", "1:1"))
    }

    @Test
    fun `restrict set expands to every uri sharing the stamp`() {
        val stamps = listOf(
            "content://old" to "100:50",
            "content://new" to "100:50",
            "content://other" to "9:9",
        )
        assertEquals(
            setOf("content://new", "content://old"),
            expandRestrictUrisByStamp(stamps, setOf("content://new")),
        )
        assertTrue(expandRestrictUrisByStamp(stamps, emptySet()).isEmpty())
        assertEquals(
            setOf("content://missing"),
            expandRestrictUrisByStamp(stamps, setOf("content://missing")),
        )
    }

    @Test
    fun `session usages skip fingerprint-only alias rows`() {
        val rows = listOf(
            RagChunkEntity(
                id = 1, sessionId = "s", docUri = "content://real", docName = "a.pdf",
                mimeType = "application/pdf", chunkIndex = FINGERPRINT_CHUNK_INDEX, text = "10:20",
                createdAt = 1,
            ),
            RagChunkEntity(
                id = 2, sessionId = "s", docUri = "content://real", docName = "a.pdf",
                mimeType = "application/pdf", chunkIndex = 0, text = "hello world",
                createdAt = 1,
            ),
            RagChunkEntity(
                id = 3, sessionId = "s", docUri = "content://alias", docName = "a.pdf",
                mimeType = "application/pdf", chunkIndex = FINGERPRINT_CHUNK_INDEX, text = "10:20",
                createdAt = 2,
            ),
        )
        val usages = sessionDocUsages(rows)
        assertEquals(listOf("content://real"), usages.map { it.uri })
        assertEquals("hello world".length, usages.single().contentChars)
    }
}
