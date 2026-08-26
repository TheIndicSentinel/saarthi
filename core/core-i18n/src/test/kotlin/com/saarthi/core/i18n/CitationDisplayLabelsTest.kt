package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationDisplayLabelsTest {

    @Test
    fun `Hindi citation labels use localized header and page word`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        assertEquals("स्रोत:", labels.sourcesHeader)
        assertEquals("पृष्ठ", labels.pageSingle)
        assertEquals("अवलोकन", labels.overview)
    }

    @Test
    fun `Marathi citation labels use strot header`() {
        val labels = SupportedLanguage.MARATHI.citationDisplayLabels()
        assertTrue(labels.sourcesHeader.startsWith("स्त्रोत"))
    }
}
