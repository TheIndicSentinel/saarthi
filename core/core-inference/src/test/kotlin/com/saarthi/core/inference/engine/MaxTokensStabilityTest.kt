package com.saarthi.core.inference.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MaxTokensStabilityTest {

    @Test
    fun `first load uses the live ladder`() {
        assertEquals(
            4096,
            stabilizeEffectiveMaxTokens(
                newlyCalculated = 4096,
                pinnedForThisModel = 0,
                sameModelAsPin = false,
                cpuCrashCount = 0,
            ),
        )
    }

    @Test
    fun `reload does not drop from 4096 to 2048 on a headroom flap`() {
        assertEquals(
            4096,
            stabilizeEffectiveMaxTokens(
                newlyCalculated = 2048,
                pinnedForThisModel = 4096,
                sameModelAsPin = true,
                cpuCrashCount = 0,
            ),
        )
    }

    @Test
    fun `reload does not jump from 2048 to 4096 when RAM recovers`() {
        assertEquals(
            2048,
            stabilizeEffectiveMaxTokens(
                newlyCalculated = 4096,
                pinnedForThisModel = 2048,
                sameModelAsPin = true,
                cpuCrashCount = 0,
            ),
        )
    }

    @Test
    fun `crash recovery may still lower the window`() {
        assertEquals(
            1536,
            stabilizeEffectiveMaxTokens(
                newlyCalculated = 1536,
                pinnedForThisModel = 4096,
                sameModelAsPin = true,
                cpuCrashCount = 1,
            ),
        )
    }

    @Test
    fun `a different model ignores the previous pin`() {
        assertEquals(
            2048,
            stabilizeEffectiveMaxTokens(
                newlyCalculated = 2048,
                pinnedForThisModel = 4096,
                sameModelAsPin = false,
                cpuCrashCount = 0,
            ),
        )
    }
}
