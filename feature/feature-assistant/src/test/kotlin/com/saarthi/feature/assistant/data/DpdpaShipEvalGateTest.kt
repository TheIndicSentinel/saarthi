package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5.5 — DPDPA ship eval gate (release checklist JVM replay). */
class DpdpaShipEvalGateTest {

    @Test
    fun `ship eval gate covers required checklist cases`() {
        val ids = dpdpaShipEvalCases().map { it.id }
        for (required in DPDPA_SHIP_EVAL_REQUIRED_IDS) {
            assertTrue("missing ship eval case: $required", ids.contains(required))
        }
    }

    @Test
    fun `dpdpa ship eval gate passes all cases`() {
        for (case in dpdpaShipEvalCases()) {
            val metrics = runDpdpaShipEvalCase(case)
            assertDpdpaShipEvalCase(case, metrics)
        }
    }
}
