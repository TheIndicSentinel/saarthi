package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseMarkerParserTest {

    @Test
    fun `parse extracts memories correctly`() {
        val raw = "Sure! [SAARTHI_MEMORY key=\"test_key\" value=\"test_value\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("Sure!", result.cleanText)
        assertEquals(1, result.memories.size)
        assertEquals("test_key", result.memories[0].key)
        assertEquals("test_value", result.memories[0].value)
    }

    @Test
    fun `parse extracts relative reminders correctly`() {
        val raw = "I'll remind you. [SAARTHI_REMINDER text=\"Call Mom\" delay_minutes=\"30\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("I'll remind you.", result.cleanText)
        assertEquals(1, result.reminders.size)
        assertEquals("Call Mom", result.reminders[0].text)
        assertEquals(30, result.reminders[0].delayMinutes)
    }

    @Test
    fun `parse extracts absolute reminders correctly`() {
        val raw = "Okay. [SAARTHI_REMINDER text=\"Meeting\" time=\"14:00\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("Okay.", result.cleanText)
        assertEquals(1, result.reminders.size)
        assertEquals("Meeting", result.reminders[0].text)
        assertEquals("14:00", result.reminders[0].time)
    }

    @Test
    fun `stripAll removes special model turn markers`() {
        val raw = "<start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\nHi"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("user\nHello\nmodel\nHi", result.cleanText)
    }
}
