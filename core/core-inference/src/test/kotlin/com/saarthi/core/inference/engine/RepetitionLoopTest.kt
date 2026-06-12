package com.saarthi.core.inference.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isRepetitionLoop] is the streaming guard that stops a stuck small model.
 * The previous version false-positived on legitimate document/legal answers
 * (which recur domain terms), truncating nearly every grounded reply at
 * ~80–190 tokens. These tests pin BOTH directions: genuine consecutive loops
 * are caught; coherent answers that merely reuse a term are NOT.
 */
class RepetitionLoopTest {

    // ── Must NOT fire on legitimate answers (the regression these fix) ─────────

    @Test
    fun `legal answer that reuses domain terms is not a loop`() {
        val answer =
            "Under the Act, a Data Fiduciary must process personal data only with " +
                "the consent of the Data Principal. The Data Fiduciary must protect that " +
                "personal data with reasonable safeguards, and a Data Principal may ask the " +
                "Data Fiduciary for a summary of the personal data being processed."
        assertFalse("Reusing 'Data Fiduciary' / 'personal data' must not count as a loop",
            isRepetitionLoop(answer.takeLast(240)))
    }

    @Test
    fun `a numbered list is not a loop`() {
        val list =
            "Rights of a Data Principal: 1. the right to access a summary. " +
                "2. the right to correction and erasure. 3. the right to nominate. " +
                "4. the right to grievance redressal."
        assertFalse("List structure must not count as a loop", isRepetitionLoop(list.takeLast(240)))
    }

    @Test
    fun `normal prose with an occasional repeated word is fine`() {
        val prose = "The penalty can be significant. The Board decides the penalty based on " +
            "the nature and gravity of the breach, and the penalty may be reduced for cooperation."
        assertFalse(isRepetitionLoop(prose.takeLast(240)))
    }

    @Test
    fun `short text never triggers`() =
        assertFalse(isRepetitionLoop("penalty penalty"))

    // ── Must fire on genuine degenerate loops ──────────────────────────────────

    @Test
    fun `a phrase repeated back-to-back three times is a loop`() {
        val loop = "The penalty is 250 crore. ".repeat(5)
        assertTrue("Consecutive phrase repetition must be caught", isRepetitionLoop(loop.takeLast(240)))
    }

    @Test
    fun `a single token repeated many times is a loop`() {
        val loop = "consent " + "data ".repeat(12)
        assertTrue("Back-to-back token repetition must be caught", isRepetitionLoop(loop.takeLast(240)))
    }

    @Test
    fun `an A B A B alternating loop is caught`() {
        val loop = "yes no ".repeat(20)
        assertTrue(isRepetitionLoop(loop.takeLast(240)))
    }

    @Test
    fun `state-name list loop is caught`() {
        // The classic Gemma 1B loop the original guard targeted.
        val loop = "Maharashtra is a state. ".repeat(6)
        assertTrue(isRepetitionLoop(loop.takeLast(240)))
    }
}
