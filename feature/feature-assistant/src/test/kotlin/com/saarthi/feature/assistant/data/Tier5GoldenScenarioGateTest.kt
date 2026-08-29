package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 5 — golden scenario gate for active-doc and substance retrieval. */
class Tier5GoldenScenarioGateTest {

    @Test
    fun `tier5 gate covers required scenario ids`() {
        val ids = tier5GoldenScenarioCases().map { it.id }
        for (required in TIER5_GOLDEN_SCENARIO_REQUIRED_IDS) {
            assertTrue("missing tier5 case: $required", ids.contains(required))
        }
    }

    @Test
    fun `tier5 golden scenario gate passes all cases`() {
        for (case in tier5GoldenScenarioCases()) {
            val metrics = runTier5ScenarioCase(case)
            assertTier5ScenarioCase(case, metrics)
        }
    }
}
