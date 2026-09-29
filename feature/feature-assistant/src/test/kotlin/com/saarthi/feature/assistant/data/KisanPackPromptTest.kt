package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KisanPackPromptTest {

    @Test
    fun `trimToBoundary cuts at the last sentence end within the limit`() {
        val text = "PM-KISAN pays ₹6000 a year. It is paid in three instalments. Eligibility applies."
        assertEquals("PM-KISAN pays ₹6000 a year.", trimToBoundary(text, 40))
    }

    @Test
    fun `trimToBoundary honours the danda and line breaks`() {
        assertEquals("पहला वाक्य।", trimToBoundary("पहला वाक्य। दूसरा वाक्य बहुत लंबा है", 20))
        assertEquals("line one", trimToBoundary("line one\nline two is long", 12))
    }

    @Test
    fun `trimToBoundary returns empty when no boundary fits and whole text when it fits`() {
        assertEquals("", trimToBoundary("no terminator anywhere in this text", 10))
        assertEquals("short.", trimToBoundary("short.", 50))
    }

    @Test
    fun `pack prompt never slices a note mid-sentence`() {
        val sentence = "Farmers with cultivable land receive support under this scheme. "
        val longNote = sentence.repeat(200)
        val prompt = buildKisanPackPrompt(
            question = "What is PM-KISAN?",
            chunks = listOf(RetrievedChunk(text = longNote, docName = "PM-KISAN — Central", score = 1.0)),
            lang = SupportedLanguage.ENGLISH,
            state = "",
            packPublishedAt = "",
            maxContextTokens = 1536,
        )
        val notes = prompt.substringAfter("=== REFERENCE NOTES ===\n").substringBefore("\n=== END NOTES ===")
        assertTrue("Notes must end on a sentence boundary. Got tail: ${notes.takeLast(40)}", notes.endsWith("."))
        assertFalse("Note must have been trimmed to fit", notes.length >= longNote.length)
    }
}
