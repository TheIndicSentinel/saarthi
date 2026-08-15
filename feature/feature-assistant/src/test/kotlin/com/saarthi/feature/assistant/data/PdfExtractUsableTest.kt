package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExtractUsableTest {

    @Test
    fun `35-char junk text layer is not usable`() {
        assertFalse(pdfExtractLooksUsable("Page 1\n01/08/2026\nHDFC"))
    }

    @Test
    fun `page marker alone does not count as substance`() {
        assertFalse(pdfExtractLooksUsable("--- Page 1 ---\nHi"))
    }

    @Test
    fun `real digital extract with many letters is usable`() {
        val body = "This nondisclosure agreement is entered into by the parties. ".repeat(4)
        assertTrue(pdfExtractLooksUsable("--- Page 1 ---\n$body"))
    }

    @Test
    fun `number-heavy statement of enough length is usable`() {
        val rows = (1..20).joinToString("\n") { "2026-08-0$it  1200.50  34000.00" }
        assertTrue(pdfExtractLooksUsable(rows))
    }

    @Test
    fun `null and blank are not usable`() {
        assertFalse(pdfExtractLooksUsable(null))
        assertFalse(pdfExtractLooksUsable("   "))
    }
}
