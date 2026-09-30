package com.saarthi.core.inference.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private val tool = CalculatorFunction

    @Test
    fun `computes the expression the model passes`() {
        assertEquals(
            """{"expression": "2500*0.8*1.18", "result": "2360"}""",
            tool.execute("""{"expression": "2500*0.8*1.18"}"""),
        )
        assertEquals(
            """{"expression": "(180-60)/60 + 60/40", "result": "3.5"}""",
            tool.execute("""{"expression":"(180-60)/60 + 60/40"}"""),
        )
    }

    @Test
    fun `reports an error instead of guessing`() {
        assertTrue(tool.execute("""{"expression": "2 +"}""").contains("\"error\""))
        assertTrue(tool.execute("""{}""").contains("\"error\""))
    }

    @Test
    fun `description names the calculate function`() {
        assertTrue(CalculatorFunction.DESCRIPTION.contains("\"name\": \"calculate\""))
    }
}
