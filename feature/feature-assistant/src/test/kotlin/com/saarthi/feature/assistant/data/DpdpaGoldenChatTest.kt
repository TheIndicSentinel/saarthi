package com.saarthi.feature.assistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 5 P22 — DPDPA golden replay extras not covered by [DpdpaShipEvalGateTest].
 */
class DpdpaGoldenChatTest {

    private val act = DpdpaActFixture.doc
    private val docs = listOf(act)

    @Test
    fun `collapse preserves chapter span width for highlights`() {
        val retrieved = goldenSessionRetrieve(
            query = "Highlights from chapter VI",
            entities = goldenDocsToEntities(docs),
            sessionFiles = docs.map { it.uri to it.name },
        ).retrieved
        val body = retrieved.filter { it.chunkIndex >= 0 && it.docUri == act.uri }
            .sortedBy { it.chunkIndex }
        val viRun = body.filter { it.chunkIndex >= body.first { it.text.contains("CHAPTER VI") }.chunkIndex }
        assertTrue("span chunks=${viRun.size}", viRun.size >= 2)
    }
}
