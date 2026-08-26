package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicSourcesFooterTest {

    private val englishLabels = SupportedLanguage.ENGLISH.citationDisplayLabels()

    private fun chunk(
        text: String,
        docName: String = "bf1f0e9f04e6fb4f8fef35e82c42.pdf",
        score: Double = 5.0,
        chunkIndex: Int = 2,
    ) = RetrievedChunk(text, docName, score, chunkIndex)

    @Test
    fun `stripModelSourcesBlock removes hash Sources footer`() {
        val body = "Penalties may be imposed by the Board."
        val raw = "$body\n\nSources:\n[1] bf1f0e9f04e6fb4f8fef35e82c42 · p.17\n[2] bf1f0e9f04e6fb4f8fef35e82c42"
        assertEquals(body, stripModelSourcesBlock(raw, englishLabels))
    }

    @Test
    fun `stripModelSourcesBlock removes bare index Sources line`() {
        val body = "Board functions as a digital office."
        val raw = "$body\n\nSources: [1], [2], [3], [4]"
        assertEquals(body, stripModelSourcesBlock(raw, englishLabels))
    }

    @Test
    fun `stripModelSourcesBlock keeps prose that mentions sources in body`() {
        val text = "The Act lists several sources of authority in chapter one."
        assertEquals(text, stripModelSourcesBlock(text, englishLabels))
    }

    @Test
    fun `formatPageRangeForUser converts p and pp`() {
        assertEquals("page 17", formatPageRangeForUser("p.17", englishLabels))
        assertEquals("pages 10-12", formatPageRangeForUser("pp.10-12", englishLabels))
    }

    @Test
    fun `formatPageRangeForUser uses Hindi page word`() {
        val hindi = SupportedLanguage.HINDI.citationDisplayLabels()
        assertEquals("पृष्ठ 17", formatPageRangeForUser("p.17", hindi))
    }

    @Test
    fun `formatUserCitationLine uses outline title and page`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val line = formatUserCitationLine(
            chunk("--- Page 17 ---\nPenalty schedule", hash, chunkIndex = 5),
            outline,
            englishLabels,
        )
        assertTrue(line.contains("Digital Personal Data"))
        assertTrue(line.contains("page 17"))
        assertFalse(line.contains("bf1f0e9f"))
    }

    @Test
    fun `formatUserCitationLine uses overview for outline chunks`() {
        val line = formatUserCitationLine(
            RetrievedChunk("outline text", "doc.pdf", 1.0, -1),
            emptyMap(),
            englishLabels,
        )
        assertTrue(line.endsWith("overview"))
    }

    @Test
    fun `buildDeterministicSourcesFooter dedupes and caps`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val chunks = listOf(
            chunk("--- Page 17 ---\na", hash, chunkIndex = 10),
            chunk("--- Page 17 ---\nb", hash, chunkIndex = 11),
            chunk("--- Page 3 ---\nc", "NDA.pdf", chunkIndex = 1),
        )
        val footer = buildDeterministicSourcesFooter(chunks, outline, englishLabels, maxSources = 3)
        assertTrue(footer.startsWith("Sources:"))
        assertTrue(footer.contains("Digital Personal Data · page 17"))
        assertTrue(footer.contains("NDA · page 3"))
        assertEquals(2, footer.lines().size - 1) // one Sources label + 2 cite lines
    }

    @Test
    fun `buildDeterministicSourcesFooter uses Hindi header`() {
        val hindi = SupportedLanguage.HINDI.citationDisplayLabels()
        val footer = buildDeterministicSourcesFooter(
            listOf(chunk("--- Page 2 ---\ntext", "doc.pdf", chunkIndex = 1)),
            emptyMap(),
            hindi,
        )
        assertTrue(footer.startsWith("स्रोत:"))
        assertTrue(footer.contains("पृष्ठ 2"))
    }

    @Test
    fun `applyDeterministicSourcesFooter uses act title not outline auto`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = "Digital Personal Data Protection Act, 2023\nDocument outline (auto-detected headings):\n- CHAPTER I"
        val model = "Overview of the Act.\n\nSources:\n[1] Document outline auto"
        val out = applyDeterministicSourcesFooter(
            model,
            listOf(
                RetrievedChunk(
                    text = outline,
                    docName = hash,
                    score = 1.0,
                    chunkIndex = -1,
                ),
            ),
            mapOf(hash to outline),
            englishLabels,
        )
        assertFalse(out.contains("Document outline auto"))
        assertFalse(out.contains("bf1f0e9f"))
        assertTrue(out.contains("Digital Personal Data"))
    }

    @Test
    fun `applyDeterministicSourcesFooter replaces model footer`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val model = "The penalty may extend to two hundred crore rupees.\n\nSources:\n[1] Document outline auto"
        val out = applyDeterministicSourcesFooter(
            model,
            listOf(chunk("--- Page 17 ---\nSchedule", hash)),
            outline,
            englishLabels,
        )
        assertTrue(out.contains("two hundred crore"))
        assertFalse(out.contains("Document outline auto"))
        assertFalse(out.contains("[1]"))
        assertTrue(out.contains("Sources:"))
        assertTrue(out.contains("Digital Personal Data · page 17"))
    }
}
