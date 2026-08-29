package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 4.12 — multi-doc golden scenario gate. */
class Tier4GoldenScenarioGateTest {

    @Test
    fun `tier4 gate covers required scenario ids`() {
        val ids = tier4GoldenScenarioCases().map { it.id }
        for (required in TIER4_GOLDEN_SCENARIO_REQUIRED_IDS) {
            assertTrue("missing tier4 case: $required", ids.contains(required))
        }
    }

    @Test
    fun `tier4 golden scenario gate passes all cases`() {
        for (case in tier4GoldenScenarioCases()) {
            val metrics = runTier4ScenarioCase(case)
            assertTier4ScenarioCase(case, metrics)
        }
    }
}
