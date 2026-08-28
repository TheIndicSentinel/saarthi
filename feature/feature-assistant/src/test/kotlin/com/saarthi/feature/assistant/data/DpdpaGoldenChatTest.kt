package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 5 P22 — replay DPDPA chat turns through retrieve → prompt → citation gating.
 */
class DpdpaGoldenChatTest {

    private val act = DpdpaActFixture.doc
    private val docs = listOf(act)

    private fun run(query: String, attachmentsThisTurn: Boolean = false): GoldenPromptMetrics =
        runGoldenTurn(
            GoldenTurnSpec(
                query = query,
                attachmentsThisTurn = attachmentsThisTurn,
                boostDocUris = if (attachmentsThisTurn) setOf(act.uri) else emptySet(),
            ),
            docs,
        )

    @Test
    fun `first attach overview has grounded rag block`() {
        val metrics = run(ATTACH_OVERVIEW_QUERY, attachmentsThisTurn = true)
        assertTrue("overview ragChars=${metrics.ragChars}", metrics.ragChars > 80)
        assertTrue(metrics.chunkCount > 0)
        assertEquals(RagTurnMode.DOCUMENT_GROUNDED, metrics.turnMode)
    }

    @Test
    fun `chapter VI highlights retrieves span with anchored chunks`() {
        val metrics = run("Highlights from chapter VI")
        assertTrue(metrics.retrieved.any { it.text.contains("CHAPTER VI", ignoreCase = true) })
        assertTrue(metrics.retrieved.any { it.text.contains("children", ignoreCase = true) })
        assertTrue(metrics.anchoredChunkCount >= 1)
        assertTrue(metrics.ragChars > 100)
    }

    @Test
    fun `chapter VII highlights retrieves rights chapter span`() {
        val metrics = run("What does chapter VII say about rights")
        assertTrue(metrics.retrieved.any { it.text.contains("CHAPTER VII", ignoreCase = true) })
        assertTrue(metrics.retrieved.any { it.text.contains("Data Principal", ignoreCase = true) })
    }

    @Test
    fun `special provisions query surfaces special provisions section`() {
        val metrics = run("What are special provisions in this act")
        assertTrue(
            metrics.retrieved.any { it.text.contains("SPECIAL PROVISIONS", ignoreCase = true) },
        )
        assertTrue(metrics.ragChars > 80)
    }

    @Test
    fun `penalties query includes schedule and section 33 siblings`() {
        val metrics = run("What are the monetary penalties and amounts in the schedule")
        val texts = metrics.retrieved.joinToString("\n") { it.text }
        assertTrue(texts.contains("THE SCHEDULE", ignoreCase = true))
        assertTrue(texts.contains("33. Penalties") || texts.contains("PENALTIES AND ADJUDICATION"))
        assertTrue(metrics.shouldCite)
        assertTrue(metrics.strongMatch)
    }

    @Test
    fun `general knowledge opt-out does not cite attached act`() {
        val metrics = run("Ignore the document and explain how rainbows form")
        assertEquals(RagTurnMode.GENERAL_KNOWLEDGE, metrics.turnMode)
        assertFalse(metrics.shouldCite)
    }

    @Test
    fun `photosynthesis with indexed docs skips document citations`() {
        val metrics = run("What is photosynthesis")
        assertEquals(RagTurnMode.GENERAL_KNOWLEDGE, metrics.turnMode)
        assertFalse(metrics.shouldCite)
    }

    @Test
    fun `collapse preserves chapter span width for highlights`() {
        val retrieved = goldenSessionRetrieve(
            query = "Highlights from chapter VI",
            entities = goldenDocsToEntities(docs),
            sessionFiles = docs.map { it.uri to it.name },
        )
        val body = retrieved.filter { it.chunkIndex >= 0 && it.docUri == act.uri }
            .sortedBy { it.chunkIndex }
        val viRun = body.filter { it.chunkIndex >= body.first { it.text.contains("CHAPTER VI") }.chunkIndex }
        assertTrue("span chunks=${viRun.size}", viRun.size >= 2)
    }
}
