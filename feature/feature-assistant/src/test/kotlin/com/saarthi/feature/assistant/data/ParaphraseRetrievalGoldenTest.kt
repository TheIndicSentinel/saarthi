package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P25 — paraphrase retry retrieves fiduciary chapter for colloquial asks. */
class ParaphraseRetrievalGoldenTest {

    @Test
    fun `data boss duties surfaces fiduciary obligations chapter`() {
        val metrics = runGoldenTurn(
            GoldenTurnSpec(query = "What are data boss duties in this act"),
            listOf(DpdpaActFixture.doc),
        )
        val texts = metrics.retrieved.joinToString("\n") { it.text }
        assertTrue(texts.contains("Data Fiduciary", ignoreCase = true))
        assertTrue(texts.contains("OBLIGATIONS", ignoreCase = true))
        assertTrue(metrics.ragChars > 80)
    }
}
