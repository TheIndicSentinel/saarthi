package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0.1 — organic vs structural anchor scoring. */
class RetrievalScoringTest {

  @Test
  fun `structural anchor uses rank floor not inflated score`() {
    val anchor = RetrievedChunk(
      text = "CHAPTER VIII",
      docName = "act.pdf",
      score = 0.0,
      chunkIndex = 3,
      docUri = "content://act",
      structuralAnchor = StructuralAnchorKind.HEADING,
    )
    assertEquals(0.0, anchor.score, 0.0)
    assertEquals(STRUCTURAL_ANCHOR_RANK_FLOOR, anchor.rankingScore(), 0.0)
  }

  @Test
  fun `strong lexical requires bm25 or query overlap not anchor alone`() {
    val anchorOnly = RetrievedChunk(
      text = "CHAPTER II\nObligations of Data Fiduciary",
      docName = "act.pdf",
      score = 0.0,
      chunkIndex = 2,
      docUri = "content://act",
      structuralAnchor = StructuralAnchorKind.HEADING,
    )
    assertFalse(
      hasStrongLexicalRetrievalHit(
        query = "Explain black holes to a kid",
        retrieved = listOf(anchorOnly),
      ),
    )
    assertTrue(
      hasStrongLexicalRetrievalHit(
        query = "What are obligations in the act",
        retrieved = listOf(anchorOnly),
      ),
    )
  }

  @Test
  fun `organic bm25 above threshold is strong without overlap`() {
    val hit = RetrievedChunk(
      text = "miscellaneous closing text",
      docName = "act.pdf",
      score = STRONG_RAG_MATCH_SCORE + 0.5,
      chunkIndex = 1,
      docUri = "content://act",
    )
    assertTrue(hasStrongLexicalRetrievalHit("random unrelated ask", listOf(hit)))
  }

  @Test
  fun `top organic ignores structural anchor floor`() {
    val hits = listOf(
      RetrievedChunk("a", "a.pdf", 0.0, 1, "u1", StructuralAnchorKind.HEADING),
      RetrievedChunk("b", "b.pdf", 4.0, 2, "u2"),
    )
    assertEquals(4.0, topOrganicRetrievalScore(hits), 0.0)
  }
}
