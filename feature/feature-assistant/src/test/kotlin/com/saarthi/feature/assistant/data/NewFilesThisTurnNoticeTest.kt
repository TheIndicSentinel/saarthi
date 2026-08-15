package com.saarthi.feature.assistant.data

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
        val line = newFilesThisTurnNotice(listOf("Account Statement", "NDA"))
        assertTrue(line.startsWith("New files this turn: Account Statement; NDA"))
        assertTrue(line.contains("do not reuse answers about earlier documents"))
    }
}
