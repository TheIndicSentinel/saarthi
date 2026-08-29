package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B — question-shaped retrieval detectors and anchors. */
class PhaseBRetrievalTest {

  @Test
    fun `extract in-doc comparison sides from weather vs climate`() {
        val sides = extractInDocComparisonSides(
            "What is the difference between weather and climate?",
        )
        assertTrue(sides.any { it.contains("weather", ignoreCase = true) })
        assertTrue(sides.any { it.contains("climate", ignoreCase = true) })
    }

    @Test
    fun `in-doc concept compare does not enable equal slots on two files`() {
        assertFalse(
            shouldUseEqualSlotsCompare(
                "What is the difference between weather and climate?",
                docCount = 2,
            ),
        )
    }

    @Test
    fun `pick comparison chunks surfaces both weather and climate passages`() {
        val uri = DynamicEarthEducatorFixture.URI
        val chunks = listOf(
            RagChunkEntity(
                sessionId = "s",
                docUri = uri,
                docName = DynamicEarthEducatorFixture.NAME,
                mimeType = "application/pdf",
                chunkIndex = 0,
                text = "Weather is what we get; climate is what we expect over decades.",
            ),
            RagChunkEntity(
                sessionId = "s",
                docUri = uri,
                docName = DynamicEarthEducatorFixture.NAME,
                mimeType = "application/pdf",
                chunkIndex = 1,
                text = "Wind circulates when temperature differences create pressure variation.",
            ),
        )
        val picked = pickInDocComparisonChunkEntities(
            chunks,
            "What is the difference between weather and climate?",
            maxPerSide = 1,
        )
        assertTrue(picked.isNotEmpty())
        val joined = picked.joinToString("\n") { it.text }
        assertTrue(joined.contains("Weather is what we get", ignoreCase = true))
        assertTrue(joined.contains("climate", ignoreCase = true))
    }

    @Test
    fun `absence inventory query detected for topics not discussed`() {
        assertTrue(
            isAbsenceInventoryQuery(
                "Which topics related to climate change are not discussed in the document?",
            ),
        )
    }

    @Test
    fun `set enumeration query widens span preservation`() {
        val q = "What are the main components of Earth's climate system?"
        assertTrue(isSetEnumerationQuery(q))
        assertTrue(isSpanPreservingQuery(q))
        assertEquals(RagAnswerShape.LIST, detectRagAnswerShape(q, metaOverview = false))
    }

    @Test
    fun `activities query activates topic category`() {
        assertTrue(
            activeTopicCategories(
                "What activities or experiments does the guide recommend?",
            ).any { it.id == "activities" },
        )
    }

    @Test
    fun `filename token match rejects earth inside dynamicearth`() {
        assertFalse(filenameTokenMatchesQuery("earth", "dynamicearth"))
        assertTrue(filenameTokenMatchesQuery("guide", "guide"))
    }

    @Test
    fun `earth token does not named-match DynamicEarth filename alone`() {
        val docs = listOf(
            DynamicEarthEducatorFixture.URI to DynamicEarthEducatorFixture.NAME,
            "content://act" to "DPDP_Act_2023.pdf",
        )
        assertFalse(matchNamedDocs("How does earth affect climate", docs).contains(DynamicEarthEducatorFixture.URI))
    }
}
