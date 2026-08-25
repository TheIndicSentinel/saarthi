package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeSmallFileTest {

    private fun chunk(
        docUri: String,
        index: Int,
        text: String,
        score: Double = 0.0,
    ) = RetrievedChunk(
        text = text,
        docName = docUri,
        score = score,
        chunkIndex = index,
        docUri = docUri,
    )

    @Test
    fun `small file returns every chunk in document order`() {
        val full = (0..3).map { i -> chunk("note.txt", i, "word".repeat(20) + i) }
        val retrieved = listOf(full[2].copy(score = 1.2))
        val expanded = expandWholeSmallFiles(
            retrieved,
            mapOf("note.txt" to full),
            wholeFileChars = 3_000,
        )
        assertEquals(listOf(0, 1, 2, 3), expanded.map { it.chunkIndex })
        assertTrue(expanded.all { it.docUri == "note.txt" })
    }

    @Test
    fun `file over the cap keeps BM25 hits only`() {
        val full = (0..4).map { i -> chunk("nda.pdf", i, "x".repeat(800)) }
        val retrieved = listOf(full[3].copy(score = 2.0), full[1].copy(score = 1.0))
        val expanded = expandWholeSmallFiles(
            retrieved,
            mapOf("nda.pdf" to full),
            wholeFileChars = 3_000,
        )
        assertEquals(listOf(3, 1), expanded.map { it.chunkIndex })
        assertTrue(full.sumOf { it.text.length } > 3_000)
    }

    @Test
    fun `mixed session expands only the small file`() {
        val small = (0..2).map { i -> chunk("memo.txt", i, "ok$i") }
        val large = (0..4).map { i -> chunk("nda.pdf", i, "y".repeat(800)) }
        val retrieved = listOf(
            large[2].copy(score = 3.0),
            small[1].copy(score = 0.5),
        )
        val expanded = expandWholeSmallFiles(
            retrieved,
            mapOf("memo.txt" to small, "nda.pdf" to large),
            wholeFileChars = 3_000,
        )
        assertEquals(listOf("nda.pdf", "memo.txt", "memo.txt", "memo.txt"), expanded.map { it.docUri })
        assertEquals(listOf(2, 0, 1, 2), expanded.map { it.chunkIndex })
    }

    @Test
    fun `empty retrieval stays empty`() {
        val full = listOf(chunk("a.txt", 0, "hi"))
        assertTrue(expandWholeSmallFiles(emptyList(), mapOf("a.txt" to full)).isEmpty())
    }

    @Test
    fun `LARGE budget expands a file that STANDARD would keep as BM25 hits`() {
        val full = (0..4).map { i -> chunk("note.txt", i, "x".repeat(800)) }
        val retrieved = listOf(full[3].copy(score = 2.0))
        val chars = full.sumOf { it.text.length }
        assertTrue(chars > 3_000)
        assertTrue(chars <= 5_000)
        val standard = expandWholeSmallFiles(retrieved, mapOf("note.txt" to full), wholeFileChars = 3_000)
        val large = expandWholeSmallFiles(retrieved, mapOf("note.txt" to full), wholeFileChars = 5_000)
        assertEquals(listOf(3), standard.map { it.chunkIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), large.map { it.chunkIndex })
    }

    @Test
    fun `whole-file budget follows prompt room`() {
        assertEquals(5_000, wholeFileCharBudget(8_000))
        assertEquals(3_000, wholeFileCharBudget(5_000))
        assertEquals(1_500, wholeFileCharBudget(1_500))
    }
}
