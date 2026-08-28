package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Test

/** Phase 6.2 — cross-encoder stays off; feature rerank + ship eval suffice. */
class CrossEncoderDeferralGateTest {

    @Test
    fun `cross encoder rerank stays disabled`() {
        assertFalse(crossEncoderRerankEnabled())
    }

    @Test
    fun `deferral gate passes with ship eval on lexical rerank`() {
        assertCrossEncoderDeferralWithShipEval()
    }
}
