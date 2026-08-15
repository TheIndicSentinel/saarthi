package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionFailureTest {

    @Test
    fun `scan little-text sentinel is an error not content`() {
        assertNotNull(extractionFailureMessage("[PDF: Scan had little readable text]"))
    }

    @Test
    fun `pdf empty sentinel is an error not content`() {
        assertEquals(
            "No readable text found in this PDF.",
            extractionFailureMessage("[PDF: No readable text found]"),
        )
    }

    @Test
    fun `real document text is not a failure`() {
        assertNull(extractionFailureMessage("Account Statement\nBalance: 12000"))
    }

    @Test
    fun `image no-text sentinel is an error`() {
        assertEquals(
            "No text detected in this image.",
            extractionFailureMessage("[Image: No text detected in this image]"),
        )
    }

    @Test
    fun `blank image is unreadable this turn`() {
        assertTrue(isUnreadableThisTurn(error = null, extractedText = null))
        assertTrue(isUnreadableThisTurn(error = "No text detected in this image.", extractedText = null))
    }

    @Test
    fun `image with OCR text is not unreadable`() {
        assertTrue(
            extractionFailureMessage("[Extracted from image]:\nINVOICE 42") == null,
        )
        assertTrue(
            !isUnreadableThisTurn(error = null, extractedText = "[Extracted from image]:\nINVOICE 42"),
        )
    }
}
