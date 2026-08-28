package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.1 — lexical baseline before any dense retrieval spike. */
class DenseRetrievalEvalGateTest {

    @Test
    fun `dense spike flag stays off for production`() {
        assertFalse(denseRetrievalSpikeEnabled())
    }

    @Test
    fun `eval gate covers required paraphrase cases`() {
        val ids = denseRetrievalEvalCases().map { it.id }
        for (required in DENSE_RETRIEVAL_EVAL_REQUIRED_IDS) {
            assertTrue("missing dense eval case: $required", ids.contains(required))
        }
    }

    @Test
    fun `lexical path passes dense eval gate without embeddings`() {
        for (case in denseRetrievalEvalCases()) {
            val metrics = runDenseRetrievalEvalCase(case)
            assertDenseRetrievalEvalCase(case, metrics)
        }
        assertTrue(denseRetrievalSpikeReadyForExperiment())
    }
}
