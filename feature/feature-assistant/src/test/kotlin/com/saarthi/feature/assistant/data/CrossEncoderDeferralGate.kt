package com.saarthi.feature.assistant.data

/**
 * Phase 6.2 — cross-encoder rerank deferral gate.
 * Feature rerank must cover ship-eval cases while [crossEncoderRerankEnabled] stays false.
 */
internal fun crossEncoderDeferralSatisfied(): Boolean = !crossEncoderRerankEnabled()

internal fun assertCrossEncoderDeferralWithShipEval() {
    check(crossEncoderDeferralSatisfied()) {
        "cross-encoder must stay disabled until spike/deferral criteria pass"
    }
    for (case in dpdpaShipEvalCases()) {
        val metrics = runDpdpaShipEvalCase(case)
        assertDpdpaShipEvalCase(case, metrics)
    }
}
