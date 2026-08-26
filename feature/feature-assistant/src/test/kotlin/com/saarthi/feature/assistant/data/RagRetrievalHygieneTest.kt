package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRetrievalHygieneTest {

  private fun chunk(
      uri: String,
      index: Int,
      score: Double,
  ) = RetrievedChunk("text $index", "doc.pdf", score, index, uri)

  @Test
  fun `collapseRedundantChunkRuns keeps two per adjacent run`() {
    val hits = listOf(
        chunk("a", 10, 12.0),
        chunk("a", 11, 11.0),
        chunk("a", 12, 10.0),
        chunk("a", 13, 9.0),
        chunk("a", 20, 8.0),
    )
    val out = collapseRedundantChunkRuns(hits, maxPerAdjacentRun = 2)
    assertEquals(3, out.size)
    assertTrue(out.any { it.chunkIndex == 10 })
    assertTrue(out.any { it.chunkIndex == 11 })
    assertTrue(out.any { it.chunkIndex == 20 })
    assertEquals(false, out.any { it.chunkIndex == 12 })
  }

  @Test
  fun `collapse preserves outline chunks`() {
    val outline = RetrievedChunk("outline", "doc.pdf", 1.0, -1, "a")
    val body = chunk("a", 5, 4.0)
    val out = collapseRedundantChunkRuns(listOf(outline, body))
    assertEquals(2, out.size)
    assertEquals(-1, out.first().chunkIndex)
  }

  @Test
  fun `extractSectionRefs finds section 15`() {
    val refs = extractSectionRefs("Breach in observance of the duties under section 15")
    assertTrue(refs.any { it.kind == "section" && it.token == "15" })
  }

  @Test
  fun `locateSectionInChunks finds section marker in body`() {
    val chunks = listOf(
        "Earlier text.",
        "15. Duties of Data Principal\n(a) comply with laws.",
        "Later penalties.",
    )
    val idx = locateSectionInChunks(chunks, SectionRef("section", "15"))
    assertEquals(1, idx)
  }

  @Test
  fun `marathi overview triggers meta routing`() {
    assertTrue(isDevanagariMetaTrigger("दस्तऐवराचा आढावा द्या"))
    assertEquals("overview", RagDocumentRepository.metaRouteReason("Document content chi overview dya"))
    assertEquals("phrase", RagDocumentRepository.metaRouteReason("attached document content chi please"))
  }

  @Test
  fun `schedule ref locates schedule chunk`() {
    val chunks = listOf(
        "Chapter text.",
        "THE SCHEDULE\n(See section 33)",
    )
    val idx = locateSectionInChunks(chunks, SectionRef("schedule", "schedule"))
    assertEquals(1, idx)
  }
}
