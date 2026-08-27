package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heading-anchored retrieval matching. When a query names a section the
 * document has a heading for, [matchHeading] returns it so retrieval can pull
 * that section to the top — fixing the production miss where "What are special
 * provisions" retrieved scattered chunks and answered in 45 tokens.
 *
 * The match must be conservative: fire on a clear section reference, stay silent
 * on partial overlap, so anchoring never hijacks an ordinary keyword query.
 */
class HeadingAnchorTest {

    private val outline = """
        Document outline (auto-detected headings):
        - PRELIMINARY
        - RIGHTS AND DUTIES OF DATA PRINCIPAL
        - SPECIAL PROVISIONS
        - THE DATA PROTECTION BOARD OF INDIA
        - PENALTIES AND ADJUDICATION
    """.trimIndent()

    private val headings get() = parseOutlineHeadings(outline)

    // ── parseOutlineHeadings ───────────────────────────────────────────────────

    @Test
    fun `outline parses to its heading lines`() {
        assertEquals(
            listOf(
                "PRELIMINARY",
                "RIGHTS AND DUTIES OF DATA PRINCIPAL",
                "SPECIAL PROVISIONS",
                "THE DATA PROTECTION BOARD OF INDIA",
                "PENALTIES AND ADJUDICATION",
            ),
            headings,
        )
    }

    @Test
    fun `outline with no heading lines parses empty`() {
        assertEquals(emptyList<String>(), parseOutlineHeadings("Some prose with no bullets."))
    }

    // ── Should match (clear section reference) ─────────────────────────────────

    @Test
    fun `penalty keyword bridge matches penalties heading`() {
        val hs = listOf(
            "CHAPTER VIII PENALTIES AND ADJUDICATION",
            "CHAPTER II OBLIGATIONS OF DATA FIDUCIARY",
        )
        assertEquals(
            "CHAPTER VIII PENALTIES AND ADJUDICATION",
            matchHeadingKeywordBridge("What does the document say about penalties", hs),
        )
        assertEquals(
            "CHAPTER VIII PENALTIES AND ADJUDICATION",
            matchHeading("What does the document say about penalties", hs),
        )
    }

    @Test
    fun `the production miss now matches its section`() {
        assertEquals("SPECIAL PROVISIONS", matchHeading("What are special provisions", headings))
    }

    @Test
    fun `plural-singular difference still matches`() {
        assertEquals("SPECIAL PROVISIONS", matchHeading("explain the special provision", headings))
    }

    @Test
    fun `filler words around the section name do not block the match`() {
        assertEquals(
            "THE DATA PROTECTION BOARD OF INDIA",
            matchHeading("tell me about the data protection board of india", headings),
        )
    }

    @Test
    fun `penalties query matches the penalties chapter`() {
        assertEquals(
            "PENALTIES AND ADJUDICATION",
            matchHeading("what penalties and adjudication apply", headings),
        )
    }

    // ── Should NOT match (partial overlap / unrelated) ─────────────────────────

    @Test
    fun `partial overlap does not anchor`() {
        // "rights" alone must not hijack the long RIGHTS AND DUTIES… heading.
        assertNull(matchHeading("what are my rights", headings))
    }

    @Test
    fun `an unrelated query matches nothing`() {
        assertNull(matchHeading("how do I withdraw my consent", headings))
    }

    @Test
    fun `a single short connective cannot anchor`() {
        // "of" / "the" are stopwords; "and" too — nothing significant to match.
        assertNull(matchHeading("and the of", headings))
    }

    @Test
    fun `more specific heading wins when two could match`() {
        val hs = listOf("DATA", "THE DATA PROTECTION BOARD OF INDIA")
        // "DATA" is a single 4-char token; the fuller heading is more specific.
        assertEquals(
            "THE DATA PROTECTION BOARD OF INDIA",
            matchHeading("about the data protection board of india", hs),
        )
    }

    @Test
    fun `token overlap finds a heading the body never repeats verbatim`() {
        val chunks = listOf(
            "Opening remarks about the act.",
            "This chapter covers penalties and adjudication for breaches.",
            "Later miscellaneous rules.",
        )
        assertEquals(1, locateHeadingInChunks(chunks, "PENALTIES AND ADJUDICATION"))
    }

    @Test
    fun `missing heading still takes the window before the next heading`() {
        val headings = listOf("SPECIAL PROVISIONS", "PENALTIES AND ADJUDICATION")
        val chunks = listOf(
            "Intro a.",
            "Intro b.",
            "Body of special rules without the title line.",
            "More special-rule body.",
            "Still special-rule body.",
            "PENALTIES AND ADJUDICATION start here.",
        )
        val window = headingAnchorWindow(chunks, "SPECIAL PROVISIONS", headings, maxChunks = 3)!!
        assertEquals(HeadingWindow(start = 2, endExclusive = 5), window)
    }

    @Test
    fun `neighbor stays in the same document`() {
        val byDoc = mapOf(
            "nda" to listOf(1L, 2L, 3L),
            "log" to listOf(10L, 11L),
        )
        assertEquals(3L, nextSameDocNeighborId(2L, "nda", byDoc))
        assertEquals(null, nextSameDocNeighborId(3L, "nda", byDoc))
        assertEquals(11L, nextSameDocNeighborId(10L, "log", byDoc))
        assertEquals(null, nextSameDocNeighborId(2L, "log", byDoc))
    }

    @Test
    fun `devanagari overview words trigger meta but sar substring does not`() {
        assertTrue(isDevanagariMetaTrigger("इसका सारांश दो"))
        assertTrue(isDevanagariMetaTrigger("विषयसूची दिखाओ"))
        assertTrue(isDevanagariMetaTrigger("संक्षेप में बताओ"))
        assertTrue(isDevanagariMetaTrigger("अवलोकन"))
        assertTrue(isDevanagariMetaTrigger("आढावा द्या"))
        assertFalse(isDevanagariMetaTrigger("प्रसार कितना है"))
        assertFalse(isDevanagariMetaTrigger("संसार के नियम"))
        assertFalse(isDevanagariMetaTrigger("what is the penalty"))
    }

    @Test
    fun `tamil and bengali numbered headings are detected`() {
        assertTrue(isLikelyHeadingLine("1. அறிமுகம்", nextLineBlank = false))
        assertTrue(isLikelyHeadingLine("2. অধ্যায় পরিচিতি", nextLineBlank = false))
        assertTrue(isLikelyHeadingLine("1. परिचय", nextLineBlank = false))
        assertFalse(isLikelyHeadingLine("1. then we walked to the bus stop", nextLineBlank = false))
    }

    @Test
    fun `indic short title followed by a blank is a heading`() {
        assertTrue(isLikelyHeadingLine("தமிழ் அறிமுகம்", nextLineBlank = true))
        assertFalse(isLikelyHeadingLine("தமிழ் அறிமுகம்", nextLineBlank = false))
    }

    @Test
    fun `latin all-caps still headings and ingest markers are not`() {
        assertTrue(isLikelyHeadingLine("INTRODUCTION", nextLineBlank = false))
        assertFalse(isLikelyHeadingLine("--- Slide 3 ---", nextLineBlank = false))
        assertFalse(isLikelyHeadingLine("--- Sheet: Sales ---", nextLineBlank = false))
        assertFalse(isLikelyHeadingLine("--- Rows 1-25 ---", nextLineBlank = false))
    }
}
