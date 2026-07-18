package com.saarthi.core.inference.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [classifyLikelyCrashCause] picks the crash-recovery diagnosis string
 * LiteRTInferenceEngine logs after detecting a process death via the
 * dead-man's-switch (litert_gen_pending/litert_init_pending). confirmedLowMemory
 * comes from ApplicationExitInfo.REASON_LOW_MEMORY — official OS evidence,
 * layered on top of the existing pending-flag heuristic to upgrade the
 * diagnosis's wording (never its priority ordering or any count/ban
 * decision — that stays exactly as it was).
 */
class CrashDiagnosisTest {

    @Test
    fun `GPU fault takes priority over everything else`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = true,
            crashedDuringGen = true,
            crashWasThisModel = true,
            crashedDuringInit = true,
            confirmedLowMemory = true,
        )
        assertTrue(cause.startsWith("GPU/NPU_FAULT"))
    }

    @Test
    fun `confirmed low memory during generation upgrades the CPU_CRASH wording`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = true,
            crashWasThisModel = true,
            crashedDuringInit = false,
            confirmedLowMemory = true,
        )
        assertTrue(cause.startsWith("CPU_CRASH"))
        assertTrue("expected confirmed wording, got: $cause", cause.contains("CONFIRMED low-memory kill"))
    }

    @Test
    fun `unconfirmed generation crash keeps the original speculative wording`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = true,
            crashWasThisModel = true,
            crashedDuringInit = false,
            confirmedLowMemory = false,
        )
        assertTrue(cause.startsWith("CPU_CRASH"))
        assertTrue(cause.contains("Possible OEM watchdog, OOM, or native LiteRT issue"))
        assertTrue("must not claim confirmation it doesn't have", !cause.contains("CONFIRMED"))
    }

    @Test
    fun `confirmed low memory during init upgrades the INIT_CRASH wording`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = false,
            crashWasThisModel = false,
            crashedDuringInit = true,
            confirmedLowMemory = true,
        )
        assertTrue(cause.startsWith("INIT_CRASH"))
        assertTrue(cause.contains("CONFIRMED low-memory kill"))
    }

    @Test
    fun `unconfirmed init crash keeps the original speculative wording`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = false,
            crashWasThisModel = false,
            crashedDuringInit = true,
            confirmedLowMemory = false,
        )
        assertTrue(cause.startsWith("INIT_CRASH"))
        assertTrue(cause.contains("likely OOM"))
        assertTrue(!cause.contains("CONFIRMED"))
    }

    @Test
    fun `generation crash for a DIFFERENT model is not attributed as this model's CPU crash`() {
        // crashedDuringGen=true but crashWasThisModel=false: the CPU_CRASH
        // branch requires both, so this must fall through toward UNKNOWN,
        // not misreport a same-model crash.
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = true,
            crashWasThisModel = false,
            crashedDuringInit = false,
            confirmedLowMemory = true,
        )
        assertEquals("UNKNOWN", cause)
    }

    @Test
    fun `no crash signal at all is UNKNOWN`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = false,
            crashWasThisModel = false,
            crashedDuringInit = false,
            confirmedLowMemory = false,
        )
        assertEquals("UNKNOWN", cause)
    }

    @Test
    fun `a same-model generation crash takes priority over an init crash when both flags are set`() {
        val cause = classifyLikelyCrashCause(
            gpuActuallyAtFault = false,
            crashedDuringGen = true,
            crashWasThisModel = true,
            crashedDuringInit = true,
            confirmedLowMemory = false,
        )
        assertTrue("CPU_CRASH must win over INIT_CRASH per the branch order", cause.startsWith("CPU_CRASH"))
    }
}
