package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0.3 — archetype × mode matrix regression lock. */
class GoldenTurnMatrixTest {

    @Test
    fun `golden turn matrix covers all archetypes`() {
        val cases = goldenTurnMatrixCases()
        val archetypes = GoldenQueryArchetype.entries
        for (archetype in archetypes) {
            assertTrue(
                "missing matrix row for $archetype",
                cases.any { it.archetype == archetype },
            )
        }
    }

    @Test
    fun `golden turn matrix mode and pipeline expectations`() {
        for (case in goldenTurnMatrixCases()) {
            val metrics = runGoldenMatrixCase(case)
            assertGoldenMatrixCase(case, metrics)
        }
    }
}
