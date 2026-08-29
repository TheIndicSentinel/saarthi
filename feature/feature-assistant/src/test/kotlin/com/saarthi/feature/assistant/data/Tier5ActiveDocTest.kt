package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.memory.db.RagChunkFtsSearch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 5.4 — active-document persistence and scope reinforcement. */
class Tier5ActiveDocTest {

  private val dao = mockk<RagChunkDao>(relaxed = true)
  private val repo = RagDocumentRepository(dao, mockk<RagChunkFtsSearch>(relaxed = true))

  @Test
  fun `persistActiveDocUri writes pointer row`() = runTest {
    coEvery { dao.getByDoc("s1", ACTIVE_DOC_POINTER_URI) } returns emptyList()
    repo.persistActiveDocUri("s1", "content://guide")
    coVerify { dao.deleteByDoc("s1", ACTIVE_DOC_POINTER_URI) }
    coVerify { dao.insertAll(any()) }
    assertEquals("content://guide", repo.resolveActiveDocUri("s1", listOf(
      SessionRagDocument("content://guide", "guide.pdf", 1L),
    )))
  }

  @Test
  fun `loadActiveDocUri restores pointer after cold start`() = runTest {
    coEvery { dao.getByDoc("s2", ACTIVE_DOC_POINTER_URI) } returns listOf(
      RagChunkEntity(
        sessionId = "s2",
        docUri = ACTIVE_DOC_POINTER_URI,
        docName = "",
        mimeType = "application/octet-stream",
        chunkIndex = ACTIVE_DOC_CHUNK_INDEX,
        text = "content://act",
      ),
    )
    val docs = listOf(
      SessionRagDocument("content://guide", "guide.pdf", 2L),
      SessionRagDocument("content://act", "act.pdf", 1L),
    )
    assertEquals("content://act", repo.loadActiveDocUri("s2", docs))
  }

  @Test
  fun `sessionDocsFromRows excludes active pointer uri`() {
    val rows = listOf(
      RagChunkEntity(
        sessionId = "s",
        docUri = ACTIVE_DOC_POINTER_URI,
        docName = "",
        mimeType = "application/octet-stream",
        chunkIndex = ACTIVE_DOC_CHUNK_INDEX,
        text = "content://x",
      ),
      RagChunkEntity(
        sessionId = "s",
        docUri = "content://real",
        docName = "real.pdf",
        mimeType = "application/pdf",
        chunkIndex = 0,
        text = "body",
      ),
    )
    val docs = sessionDocsFromRows(rows)
    assertEquals(1, docs.size)
    assertEquals("content://real", docs.single().uri)
  }
}
