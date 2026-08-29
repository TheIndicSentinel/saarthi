package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase C — citation location polish, recap caps, overlap tuning. */
class PhaseCPolishTest {

    private val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()

    @Test
    fun `extractCitationSectionHeading finds educator Section IV line`() {
        val heading = extractCitationSectionHeading(
            "Section IV — How is Weather different from Climate?\nWeather is what we get.",
        )
        assertTrue(heading?.contains("Section IV", ignoreCase = true) == true)
    }

    @Test
    fun `formatCitationLocation uses section before part fallback`() {
        val location = formatCitationLocation(
            RetrievedChunk(
                text = "Section II — Components of Earth's climate system\natmosphere hydrosphere",
                docName = "guide.pdf",
                score = 5.0,
                chunkIndex = 2,
                docUri = "content://guide",
            ),
            labels,
        )
        assertTrue(location.contains("Section II", ignoreCase = true))
        assertFalse(location.contains("not marked", ignoreCase = true))
    }

    @Test
    fun `formatCitationLocation uses part before unknown`() {
        val location = formatCitationLocation(
            RetrievedChunk(
                text = "Wind circulates when temperature differences create pressure variation.",
                docName = "guide.pdf",
                score = 5.0,
                chunkIndex = 7,
                docUri = "content://guide",
            ),
            labels,
        )
        assertEquals("part 8", location)
    }

    @Test
    fun `grounded conversation context truncates assistant replies tighter than plain chat`() {
        val longAnswer =
            "Atmosphere hydrosphere lithosphere biosphere components interact through " +
                "heat transport circulation pressure moisture exchange and regional " +
                "patterns across seasons decades centuries and geological time scales."
        val turns = listOf("How does the climate system work?" to longAnswer)
        val grounded = formatConversationContext(turns, isLarge = true, grounded = true, roomy = false)
        val plain = formatConversationContext(turns, isLarge = true, grounded = false, roomy = false)
        assertTrue(grounded.length < plain.length)
        assertTrue(grounded.contains("…"))
    }
}
