package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P26 — rewrite lexicon helps colloquial appeal asks before BM25. */
class QueryRewriteGoldenTest {

    @Test
    fun `grievance appeal query surfaces adjudication chapter`() {
        val metrics = runGoldenTurn(
            GoldenTurnSpec(query = "How does grievance and appeal work in this act"),
            listOf(DpdpaActFixture.doc),
        )
        val texts = metrics.retrieved.joinToString("\n") { it.text }
        assertTrue(texts.contains("appeal", ignoreCase = true) || texts.contains("adjudication", ignoreCase = true))
        assertTrue(metrics.ragChars > 60)
    }
}
