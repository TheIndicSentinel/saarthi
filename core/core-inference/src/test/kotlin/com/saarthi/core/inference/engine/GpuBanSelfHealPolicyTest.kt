package com.saarthi.core.inference.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins Point 4: GPU ban / crash-count self-heal must follow generation
 * success on GPU/NPU, not model init success.
 *
 * Field bug: after a GPU gen crash set a 24h ban, the next session often
 * loaded on CPU, init succeeded, and cleared the ban — so the following
 * launch retried GPU and crashed again (ping-pong). Init proves load +
 * createConversation only; [onDone] on GPU/NPU proves the failing stage.
 *
 * No Robolectric — same convention as [ConvReadyGpuBanTest]: SharedPreferences
 * persistence is untested here; the clear/keep *policy* must not regress.
 */
class GpuBanSelfHealPolicyTest {

    @Test
    fun `successful init alone must NOT clear GPU recovery state`() {
        assertFalse(shouldClearStaleGpuRecoveryOnInitSuccess())
    }

    @Test
    fun `successful GPU generation may clear GPU ban`() {
        assertTrue(shouldClearGpuBanAfterSuccessfulGeneration(wasUsingGpuOrNpu = true))
    }

    @Test
    fun `successful CPU generation must NOT clear GPU ban`() {
        assertFalse(shouldClearGpuBanAfterSuccessfulGeneration(wasUsingGpuOrNpu = false))
    }
}
