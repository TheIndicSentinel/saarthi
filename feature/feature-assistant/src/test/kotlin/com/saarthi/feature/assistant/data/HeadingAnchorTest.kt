package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
