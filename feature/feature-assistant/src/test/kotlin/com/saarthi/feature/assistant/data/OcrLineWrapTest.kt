package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isOcrLineWrap] reconstructs paragraphs from OCR'd PDF text by joining wrapped
 * lines, while preserving real paragraph / list / heading breaks. Better chunk
 * coherence → better BM25 matching and far more readable RAG context.
 */
class OcrLineWrapTest {

    // ── Should join (wrapped continuations) ────────────────────────────────────

    @Test
    fun `mid-sentence wrap joins`() {
        assertTrue(isOcrLineWrap("a data breach can attract", "a penalty of up to 250 crore."))
    }

    @Test
    fun `continuation starting with a number joins`() {
        assertTrue(isOcrLineWrap("the penalty can be up to", "250 crore rupees."))
    }

    // ── Should NOT join (real breaks) ──────────────────────────────────────────

    @Test
    fun `sentence end does not join`() {
        assertFalse(isOcrLineWrap("Consent can be withdrawn at any time.", "the next sentence here"))
    }

    @Test
    fun `colon end does not join`() {
        assertFalse(isOcrLineWrap("Rights of the individual:", "access a summary"))
    }

    @Test
    fun `next line starting uppercase is a new sentence, no join`() {
        assertFalse(isOcrLineWrap("processed by the fiduciary", "The Board may impose a penalty"))
    }

    @Test
    fun `bulleted list item keeps its break`() {
        assertFalse(isOcrLineWrap("A Data Principal has the right to", "- access a summary of their data"))
    }

    @Test
    fun `numbered list item keeps its break`() {
        assertFalse(isOcrLineWrap("Rights include", "1. the right to correction"))
    }

    @Test
    fun `hyphenated break is not space-joined`() {
        // Joining with a space would create "informa- tion"; keep the break.
        assertFalse(isOcrLineWrap("informa-", "tion of the data"))
    }

    @Test
    fun `empty lines never join`() {
        assertFalse(isOcrLineWrap("", "something"))
        assertFalse(isOcrLineWrap("something", ""))
    }

    @Test
    fun `devanagari mid-sentence wrap joins`() {
        assertTrue(isOcrLineWrap("डेटा उल्लंघन पर", "जुर्माना दो सौ पचास"))
    }

    @Test
    fun `danda ends sentence and does not join`() {
        assertFalse(isOcrLineWrap("सहमति कभी भी वापस ली जा सकती है।", "अगला वाक्य यहाँ"))
    }

    @Test
    fun `bengali mid-sentence wrap joins`() {
        assertTrue(isOcrLineWrap("ডেটা লঙ্ঘনের জন্য", "জরিমানা দুই শত"))
    }

    @Test
    fun `tamil sentence end does not join next line`() {
        assertFalse(isOcrLineWrap("ஒப்புதலை எப்போது வேண்டுமானாலும் திரும்பப் பெறலாம்.", "அடுத்த வாக்கியம்"))
    }

    @Test
    fun `statement table rows stay on their own line`() {
        assertTrue(looksLikeTableRow("12/03/2026  Grocery store  1,200.00"))
        assertFalse(
            isOcrLineWrap(
                "12/03/2026  Grocery store  1,200.00",
                "13/03/2026  Salary credit   34,000.00",
            ),
        )
    }
}
