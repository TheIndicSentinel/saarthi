package com.saarthi.core.inference.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conv-ready GPU-ban contract that [LiteRTInferenceEngine] applies
 * after an attributed GPU/NPU generation crash.
 *
 * Field bug this protects: markConvStarted/markConvReady existed on
 * [CrashRecoveryStore] but were never called around createConversation, so
 * wasConvReadyAtCrash always defaulted to true → every createConversation
 * timeout (shader/KV alloc) incorrectly 24h-banned GPU. The engine now
 * routes every createConversation through createConversationTracked(); this
 * pure decision is what that wiring feeds.
 *
 * No Robolectric — SharedPreferences persistence stays untested here (same
 * project convention as GpuAdmissionPolicyTest); the ban *policy* is what
 * must not regress.
 */
class ConvReadyGpuBanTest {

    @Test
    fun `post-conv crash bans GPU — sendMessageAsync fault`() {
        assertTrue(shouldBanGpuAfterAttributedGenCrash(wasConvReadyAtCrash = true))
    }

    @Test
    fun `createConversation crash does NOT ban GPU — shader or KV timeout`() {
        assertFalse(shouldBanGpuAfterAttributedGenCrash(wasConvReadyAtCrash = false))
    }

    @Test
    fun `default-unknown crash is treated as post-conv — conservative ban`() {
        // CrashRecoveryStore.wasConvReadyAtCrash defaults to true when the
        // pref was never written. That default must stay "ban" so an unknown
        // crash does not silently keep retrying a broken GPU path. The fix
        // for createConversation timeouts is wiring markConvStarted, not
        // flipping this default.
        assertTrue(
            "unknown/default conv-ready must ban (conservative)",
            shouldBanGpuAfterAttributedGenCrash(wasConvReadyAtCrash = true),
        )
    }
}
