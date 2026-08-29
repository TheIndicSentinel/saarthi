package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFilesThisTurnNoticeTest {

    @Test
    fun `empty names emit nothing`() {
        assertEquals("", newFilesThisTurnNotice(emptyList()))
    }

    @Test
    fun `names tell the model not to reuse earlier documents`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val line = newFilesThisTurnNotice(listOf("Account Statement", "NDA"), labels)
        assertTrue(line.startsWith("New files this turn: Account Statement; NDA"))
        assertTrue(line.contains("do not reuse answers about earlier documents"))
    }

    @Test
    fun `hindi new files notice uses localized labels`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        val line = newFilesThisTurnNotice(listOf("खाता"), labels)
        assertTrue(line.startsWith(labels.newFilesThisTurnPrefix))
        assertTrue(line.contains("खाता"))
    }
}
