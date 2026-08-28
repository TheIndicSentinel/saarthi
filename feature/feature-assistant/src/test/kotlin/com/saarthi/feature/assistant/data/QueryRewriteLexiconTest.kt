package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P26 — pre-BM25 query rewrite lexicon. */
class QueryRewriteLexiconTest {

    @Test
    fun `appeal query expands adjudication vocabulary`() {
        val expansion = queryRewriteLexiconExpansion("How does appeal and grievance work")
        assertTrue(expansion.contains("adjudication"))
        assertTrue(expansion.contains("review"))
        assertTrue(activeQueryRewriteRuleIds("appeal process").any { it.contains("appeal") })
    }

    @Test
    fun `duties query expands obligations and fiduciary`() {
        val expansion = queryRewriteLexiconExpansion("What duties does the company have")
        assertTrue(expansion.contains("obligations"))
        assertTrue(expansion.contains("fiduciary"))
    }

    @Test
    fun `data boss pattern expands fiduciary terms`() {
        val expansion = queryRewriteLexiconExpansion("data boss responsibilities")
        assertTrue(expansion.contains("Fiduciary"))
        assertTrue(expansion.contains("obligations"))
    }

    @Test
    fun `neutral query has no rewrite expansion`() {
        assertFalse(queryRewriteLexiconExpansion("What time is it").isNotEmpty())
    }

    @Test
    fun `expand retrieval query includes lexicon terms`() {
        val expanded = expandRetrievalQuery(
            "grievance and appeal process",
            listOf("Act.pdf"),
        )
        assertTrue(expanded.contains("adjudication"))
        assertTrue(expanded.contains("appeal"))
    }
}
