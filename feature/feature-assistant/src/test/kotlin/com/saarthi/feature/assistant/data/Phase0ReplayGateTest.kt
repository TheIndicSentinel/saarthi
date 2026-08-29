package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0 — replay matrix measurement gate (baseline green + full matrix smoke). */
class Phase0ReplayGateTest {

    @Test
    fun `phase0 matrix covers all registered case ids`() {
        val ids = phase0ReplayCases().map { it.id }
        for (required in PHASE0_REPLAY_ALL_IDS) {
            assertTrue("missing phase0 case: $required", ids.contains(required))
        }
    }

    @Test
    fun `phase0 baseline replay passes`() {
        for (case in phase0ReplayCases().filter { it.track == Phase0ReplayTrack.BASELINE }) {
            val metrics = runPhase0ReplayCase(case)
            assertPhase0ReplayBaseline(case, metrics)
        }
    }

    @Test
    fun `phase0 full matrix harness runs for every case`() {
        for (case in phase0ReplayCases()) {
            val metrics = runPhase0ReplayCase(case)
            assertTrue("case=${case.id} mode=${metrics.prompt.turnMode}", metrics.prompt.ragChars >= 0)
        }
    }

    @Test
    fun `phase0 tracking records citation policy A gaps without failing`() {
        val gapsByCase = phase0ReplayCases()
            .filter { it.track == Phase0ReplayTrack.TRACKING }
            .associate { case ->
                val metrics = runPhase0ReplayCase(case)
                case.id to phase0CitationPolicyAGaps(metrics, case)
            }
        // Measurement lock: gaps are expected until Phase A5; this test only ensures the recorder runs.
        assertTrue(gapsByCase.isNotEmpty())
    }
}
