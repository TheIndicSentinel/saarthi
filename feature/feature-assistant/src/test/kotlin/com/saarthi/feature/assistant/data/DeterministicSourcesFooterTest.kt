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
        docUri: String = "",
    ) = RetrievedChunk(text, docName, score, chunkIndex, docUri)

    @Test
    fun `multiFileFairSources reserves second file when it only has zero-score padding`() {
        val nda = "aaa2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val stmt = "bbb2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(
            nda to "NDA Agreement",
            stmt to "Account Statement",
        )
        val chunks = listOf(
            chunk("--- Page 1 ---\nnda text", nda, score = 10.0, chunkIndex = 1, docUri = "uri-nda"),
            chunk("--- Page 2 ---\nnda more", nda, score = 9.0, chunkIndex = 2, docUri = "uri-nda"),
            chunk("--- Page 10 ---\nstmt text", stmt, score = 0.0, chunkIndex = 5, docUri = "uri-stmt"),
        )
        val withoutFair = buildDeterministicSourcesFooter(
            chunks,
            outline,
            englishLabels,
            maxSources = 2,
            multiFileFairSources = false,
        )
        val withFair = buildDeterministicSourcesFooter(
            chunks,
            outline,
            englishLabels,
            maxSources = 2,
            multiFileFairSources = true,
        )
        assertEquals(2, withoutFair.lines().size - 1)
        assertFalse(withoutFair.contains("Account"))
        assertTrue(withoutFair.contains("page 1"))
        assertTrue(withoutFair.contains("page 2"))
        assertEquals(2, withFair.lines().size - 1)
        assertTrue(withFair.contains("NDA"))
        assertTrue(withFair.contains("Account"))
    }

    @Test
    fun `parseAssistantMessageForDisplay preserves File N disambiguation in chips`() {
        val body = "Compared both documents."
        val full = "$body\n\nSources:\nFile 1: Attached document · page 1\nFile 2: Attached document · page 5"
        val parsed = parseAssistantMessageForDisplay(full)
        assertEquals(body, parsed.body)
        assertEquals(2, parsed.sources.size)
        assertTrue(parsed.sources[0].docTitle.startsWith("File 1:"))
        assertTrue(parsed.sources[1].docTitle.startsWith("File 2:"))
    }

    @Test
    fun `shouldFairMultiFileSources enabled for compare equalSlots`() {
        assertTrue(shouldFairMultiFileSources(equalSlots = true, chunks = emptyList()))
    }

    @Test
    fun `shouldFairMultiFileSources enabled for two positive-score documents`() {
        val chunks = listOf(
            chunk("a", "a.pdf", score = 5.0, docUri = "uri-a"),
            chunk("b", "b.pdf", score = 4.0, docUri = "uri-b"),
        )
        assertTrue(shouldFairMultiFileSources(equalSlots = false, chunks = chunks))
        assertFalse(shouldFairMultiFileSources(equalSlots = false, chunks = listOf(chunks[0])))
    }

    @Test
    fun `title collision adds File N prefix for duplicate display titles`() {
        val hash1 = "aaa2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val hash2 = "bbb2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val chunks = listOf(
            chunk("--- Page 1 ---\na", hash1, score = 10.0, chunkIndex = 1, docUri = "uri-1"),
            chunk("--- Page 5 ---\nb", hash2, score = 9.0, chunkIndex = 2, docUri = "uri-2"),
        )
        val footer = buildDeterministicSourcesFooter(
            chunks,
            emptyMap(),
            englishLabels,
            maxSources = 2,
            multiFileFairSources = true,
        )
        assertTrue(footer.contains("File 1: Attached document"))
        assertTrue(footer.contains("File 2: Attached document"))
    }

    @Test
    fun `distinct titles skip File N prefix`() {
        val nda = "aaa2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val stmt = "bbb2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(nda to "NDA Agreement", stmt to "Account Statement")
        val chunks = listOf(
            chunk("--- Page 1 ---\na", nda, score = 10.0, chunkIndex = 1, docUri = "uri-nda"),
            chunk("--- Page 10 ---\nb", stmt, score = 9.0, chunkIndex = 5, docUri = "uri-stmt"),
        )
        val footer = buildDeterministicSourcesFooter(
            chunks,
            outline,
            englishLabels,
            maxSources = 2,
            multiFileFairSources = true,
        )
        assertTrue(footer.contains("NDA"))
        assertTrue(footer.contains("Account"))
        assertFalse(footer.contains("File 1:"))
    }

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
    fun `formatUserCitationLine prefixes Guide for guide filenames`() {
        val line = formatUserCitationLine(
            chunk("--- Page 2 ---\nPenalty overview", "EY_India_DPDP_Guide.pdf", chunkIndex = 1),
            emptyMap(),
            englishLabels,
        )
        assertTrue(line.startsWith("Guide:"))
        assertTrue(line.contains("page 2"))
    }

    @Test
    fun `buildDeterministicSourcesFooter includes guide prefix`() {
        val footer = buildDeterministicSourcesFooter(
            listOf(
                chunk(
                    "--- Page 2 ---\nNotes",
                    "EY_India_DPDP_Guide.pdf",
                    chunkIndex = 1,
                ),
            ),
            emptyMap(),
            englishLabels,
        )
        assertTrue(footer.contains("Guide:"))
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
