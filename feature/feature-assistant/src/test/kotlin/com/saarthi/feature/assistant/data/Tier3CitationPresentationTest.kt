package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 3 — citation presentation: Sources surface, title hygiene, location quality. */
class Tier3CitationPresentationTest {

    private val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
    private val hash = "bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"

    private fun chunk(
        text: String,
        chunkIndex: Int = 2,
        docName: String = hash,
    ) = RetrievedChunk(text, docName, score = 5.0, chunkIndex = chunkIndex)

    @Test
    fun `applyDeterministicSourcesFooter strips inline citation indices`() {
        val body = "Penalties may extend to two hundred crore rupees [1] [2]."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(chunk("--- Page 17 ---\nPenalty schedule", chunkIndex = 5)),
            mapOf(hash to "Digital Personal Data Protection Act, 2023"),
            labels,
        )
        assertFalse(out.contains("[1]"))
        assertTrue(out.contains("two hundred crore"))
        assertEquals(1, out.lowercase().split("sources:").size - 1)
    }

    @Test
    fun `safeCitationDocTitle rejects body prose fragment`() {
        val prose = "the Board may, after giving the Data Principal an opportunity"
        val title = safeCitationDocTitle(hash, null, prose, prose.length, labels)
        assertFalse(title.contains("the Board may"))
        assertTrue(title.contains("Attached document") || title.contains("Digital"))
    }

    @Test
    fun `extractPageRange ignores insane page numbers`() {
        val text = "--- Page 24 ---\nbody\n--- Page 9999 ---\nmore"
        assertEquals("p.24", extractPageRange(text))
    }

    @Test
    fun `formatCitationLocation prefers section before unknown`() {
        val location = formatCitationLocation(
            chunk("CHAPTER VIII\nPENALTIES AND ADJUDICATION\n33. Penalty for failure"),
            labels,
        )
        assertTrue(location.contains("Chapter VIII", ignoreCase = true))
        assertFalse(location.contains("not marked", ignoreCase = true))
    }

    @Test
    fun `capRedundantUnknownLocationLines keeps one unknown per title`() {
        val lines = listOf(
            "Digital Personal Data · location not marked in file",
            "Digital Personal Data · location not marked in file",
            "NDA Agreement · page 3",
        )
        val capped = capRedundantUnknownLocationLines(lines, labels)
        assertEquals(2, capped.size)
        assertEquals(1, capped.count { it.contains("not marked", ignoreCase = true) })
    }

    @Test
    fun `stripInlineModelCitationAttempts removes page of year noise`() {
        val body = "Overview (p.24 of 1997) continues here."
        val cleaned = stripInlineModelCitationAttempts(body)
        assertFalse(cleaned.contains("of 1997"))
        assertTrue(cleaned.contains("Overview"))
    }

    @Test
    fun `formatExcerptHeader uses safe title not prose`() {
        val header = formatExcerptHeader(
            1,
            hash,
            "the Board may, after giving the Data Principal an opportunity",
            chunkIndex = 3,
            outlineText = "Digital Personal Data Protection Act, 2023",
            labels = labels,
        )
        assertFalse(header.contains("the Board may"))
        assertTrue(header.contains("Digital Personal") || header.contains("Attached document"))
    }
}
