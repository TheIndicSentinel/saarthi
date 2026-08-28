package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeContextTest {

    @Test
    fun `line names the current US-locale clock and matching day-part band`() {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val band = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
        val line = buildTimeContext(SupportedLanguage.ENGLISH)
        assertTrue("Must keep the compact prefix. Got:\n$line", line.startsWith("Current local time is "))
        assertTrue("Band must match hour=$hour. Got:\n$line", line.endsWith(" — it is $band."))
        assertTrue(
            "Must be a single compact line. Got:\n$line",
            line.matches(Regex("Current local time is \\d{2}:\\d{2} on \\w{3}, \\d{1,2} \\w{3} \\d{4} — it is (morning|afternoon|evening|night)\\.")),
        )
    }

    @Test
    fun `language argument does not change the English time line`() {
        assertEquals(
            buildTimeContext(SupportedLanguage.ENGLISH),
            buildTimeContext(SupportedLanguage.HINDI),
        )
    }
}
