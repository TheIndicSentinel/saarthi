package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 4 P20 — PDF truncation notices for honest indexing caps. */
class PdfTruncationTest {

    @Test
    fun `buildPdfTruncationNotice for page cap`() {
        val notice = buildPdfTruncationNotice(
            PdfTruncationMeta(totalPages = 45, indexedPages = 25, charCapped = false),
        )
        assertNotNull(notice)
        assertTrue(notice!!.contains("1–25"))
        assertTrue(notice.contains("45"))
    }

    @Test
    fun `buildPdfTruncationNotice null when fully indexed`() {
        assertNull(
            buildPdfTruncationNotice(PdfTruncationMeta(totalPages = 10, indexedPages = 10)),
        )
    }

    @Test
    fun `index truncation line joins notices`() {
        val line = indexTruncationNoticeLine(
            listOf("Indexed pages 1–25 of 40.", "Second file capped."),
        )
        assertTrue(line.contains("1–25"))
        assertTrue(line.contains("Second file"))
    }

    @Test
    fun `capExtractedText reports char cap`() {
        val (capped, wasCapped) = capExtractedText("abcdef", 3)
        assertEquals("abc", capped)
        assertTrue(wasCapped)
        val (full, notCapped) = capExtractedText("ab", 10)
        assertEquals("ab", full)
        assertFalse(notCapped)
    }
}
