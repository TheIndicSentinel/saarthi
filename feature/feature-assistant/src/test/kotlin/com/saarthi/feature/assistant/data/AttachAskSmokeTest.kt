package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R26 — attach demo doc → ask suggested question → non-empty retrieval and
 * a prompt that names the file. JVM stand-in for the instrumented smoke
 * (no Room, no model).
 */
class AttachAskSmokeTest {

    @Test
    fun `demo document penalty question retrieves a positive hit`() {
        val chunks = chunkDocumentText(DemoDocument.TEXT, chunkSize = 600, overlap = 80)
        assertTrue(chunks.size >= 2)
        val query = DemoDocument.SUGGESTED_QUESTIONS.first()
        val route = routeQuery(query, listOf(DemoDocument.URI to DemoDocument.NAME))
        val ranked = Bm25Retriever.rank(chunks, route.expandedQuery, topK = 8)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.first().score > 0)
        assertTrue(
            "penalty question must surface the Rs 250 crore passage",
            ranked.any { hit ->
                val text = chunks[hit.index]
                text.contains("250") || text.contains("penalt", ignoreCase = true)
            },
        )
    }

    @Test
    fun `attach-turn prompt names the demo file and forbids unread cites`() {
        val name = shortDocName(DemoDocument.NAME)
        val manifest = sessionManifestLine(listOf(DemoDocument.NAME))
        val notice = newFilesThisTurnNotice(listOf(name))
        val rules = ragCitationRules(compact = false, strongMatch = true)
        assertTrue(manifest.contains(name))
        assertTrue(notice.contains(name))
        assertTrue(notice.contains("do not reuse answers about earlier documents"))
        assertTrue(rules.contains("cite each file"))
        assertFalse(manifest.contains("content://"))
    }

    @Test
    fun `blank attach send is an overview of the newest file`() {
        assertEquals(ATTACH_OVERVIEW_QUERY, attachTurnQuery("", hasAttachments = true))
        assertEquals(
            setOf(DemoDocument.URI),
            restrictUrisForAttachTurn(ATTACH_OVERVIEW_QUERY, listOf("content://old", DemoDocument.URI)),
        )
    }
}
