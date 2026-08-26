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
        assertEquals("फ़ाइल 2", labels.fileDisambigLabel(2))
    }

    @Test
    fun `Marathi citation labels use strot header`() {
        val labels = SupportedLanguage.MARATHI.citationDisplayLabels()
        assertTrue(labels.sourcesHeader.startsWith("स्त्रोत"))
    }

    @Test
    fun `Hindi citation rules example matches A4 spec shape`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        assertEquals(
            "स्रोत:\nडिजिटल पर्सनल डेटा प्रोटेक्शन अधिनियम 2023 · पृष्ठ 17",
            labels.citationRulesFooterExample(),
        )
    }

    @Test
    fun `every language has a non-empty rules example doc title`() {
        for (lang in SupportedLanguage.entries) {
            val labels = lang.citationDisplayLabels()
            assertTrue(labels.rulesExampleDocTitle.isNotBlank())
            assertTrue(labels.citationRulesFooterExample().contains(labels.sourcesHeader))
        }
    }

    @Test
    fun `every language has document role prefixes B3-2`() {
        for (lang in SupportedLanguage.entries) {
            val labels = lang.citationDisplayLabels()
            assertTrue(labels.summaryRolePrefix.isNotBlank())
            assertTrue(labels.guideRolePrefix.isNotBlank())
            assertTrue(labels.sampleRolePrefix.isNotBlank())
            assertTrue(labels.circularRolePrefix.isNotBlank())
        }
    }

    @Test
    fun `every language has excerpt-only grounding rules B3-3`() {
        for (lang in SupportedLanguage.entries) {
            val labels = lang.citationDisplayLabels()
            assertTrue(labels.excerptOnlyRule.isNotBlank())
            assertTrue(labels.excerptOnlyRuleCompact.isNotBlank())
        }
    }
}
