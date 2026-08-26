package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesBlockParserTest {

    @Test
    fun `parses English deterministic footer`() {
        val body = "The penalty may extend to two hundred crore rupees."
        val full = "$body\n\nSources:\nDigital Personal Data · page 17\nNDA · page 3"
        val parsed = parseAssistantMessageForDisplay(full)
        assertEquals(body, parsed.body)
        assertEquals("Sources:", parsed.sourcesHeader)
        assertEquals(2, parsed.sources.size)
        assertEquals("Digital Personal Data", parsed.sources[0].docTitle)
        assertEquals("page 17", parsed.sources[0].location)
    }

    @Test
    fun `parses Hindi localized footer`() {
        val hindi = SupportedLanguage.HINDI.citationDisplayLabels()
        val body = "दंड का प्रावधान है।"
        val full = "$body\n\n${hindi.sourcesHeader}\nडिजिटल पर्सनल डेटा · पृष्ठ 17"
        val parsed = parseAssistantMessageForDisplay(full)
        assertEquals(body, parsed.body)
        assertEquals(hindi.sourcesHeader, parsed.sourcesHeader)
        assertEquals("डिजिटल पर्सनल डेटा", parsed.sources[0].docTitle)
        assertEquals("पृष्ठ 17", parsed.sources[0].location)
    }

    @Test
    fun `parses legacy model footer with index prefix`() {
        val body = "Board functions as a digital office."
        val full = "$body\n\nSources:\n[1] Digital Personal Data · p.17"
        val parsed = parseAssistantMessageForDisplay(full)
        assertEquals(body, parsed.body)
        assertEquals(1, parsed.sources.size)
        assertEquals("Digital Personal Data", parsed.sources[0].docTitle)
        assertEquals("p.17", parsed.sources[0].location)
    }

    @Test
    fun `does not treat prose mentioning sources as footer`() {
        val text = "The Act lists several sources of authority in chapter one."
        val parsed = parseAssistantMessageForDisplay(text)
        assertEquals(text, parsed.body)
        assertTrue(parsed.sources.isEmpty())
    }

    @Test
    fun `stripInlineCitationIndices removes bracket refs`() {
        assertEquals(
            "Penalty applies.",
            stripInlineCitationIndices("Penalty applies.[1]"),
        )
        assertEquals(
            "See section 33 and schedule.",
            stripInlineCitationIndices("See section 33[2] and schedule."),
        )
    }

    @Test
    fun `parseDisplaySourceLine rejects internal labels`() {
        assertEquals(null, parseDisplaySourceLine("Document outline auto · page 1"))
        assertEquals(null, parseDisplaySourceLine("bf1f0e9f04e6fb4f8fef35e82c42 · p.17"))
    }
}
