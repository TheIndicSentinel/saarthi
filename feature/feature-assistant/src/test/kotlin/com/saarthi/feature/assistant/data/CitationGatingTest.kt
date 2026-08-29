package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 3 P11 — deterministic Sources gating. */
class CitationGatingTest {

    private fun bodyChunk(score: Double = 5.0, index: Int = 0) = RetrievedChunk(
        text = "--- Page 1 ---\nCHAPTER II\nObligations",
        docName = "act.pdf",
        score = score,
        chunkIndex = index,
        docUri = "content://act",
    )

  private fun hintChunk() = RetrievedChunk(
        text = buildChapterMissHint("vi"),
        docName = "act.pdf",
        score = 100.0,
        chunkIndex = RETRIEVAL_HINT_CHUNK_INDEX,
        docUri = "content://act",
    )

    @Test
    fun `no sources for general knowledge mode`() {
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.GENERAL_KNOWLEDGE,
                ragBlockChars = 500,
                retrieved = listOf(bodyChunk()),
                query = "Explain photosynthesis",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `no sources when rag block empty`() {
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 0,
                retrieved = listOf(bodyChunk()),
                query = "What are penalties in the act",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `no sources for off-topic query with weak bm25 only`() {
        val weak = bodyChunk(score = 1.0)
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 400,
                retrieved = listOf(weak),
                query = "Tell me a joke",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `no sources when off-topic despite structural anchor`() {
        val anchored = bodyChunk(score = 0.0).copy(
            structuralAnchor = StructuralAnchorKind.HEADING,
        )
        assertTrue(
            isQueryAboutDocumentForCitation(
                query = "Explain black holes",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 400,
                retrieved = listOf(anchored),
                query = "Explain black holes",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `policy A cites grounded how question with strong organic hit`() {
        val oceanChunk = RetrievedChunk(
            text = "The ocean exerts a major control on climate through heat transport.",
            docName = "guide.pdf",
            score = 8.0,
            chunkIndex = 5,
            docUri = "content://guide",
        )
        assertTrue(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 600,
                retrieved = listOf(oceanChunk),
                query = "How do oceans affect Earth's climate system?",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `sources for grounded doc query with lexical hit`() {
        assertTrue(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 800,
                retrieved = listOf(
                    bodyChunk(
                        score = 5.0,
                        index = 1,
                    ).copy(text = "--- Page 17 ---\nPenalties and adjudication factors"),
                ),
                query = "What are penalties in the act",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `sources for structure count query with outline`() {
        assertTrue(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 300,
                retrieved = listOf(
                    RetrievedChunk(
                        text = "Digital Personal Data Protection Act 2023",
                        docName = "act.pdf",
                        score = 1.0,
                        chunkIndex = -1,
                        docUri = "content://act",
                    ),
                ),
                query = "How many chapters are there",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `no sources for chapter miss hint`() {
        assertFalse(
            isRetrievalOnTypeForCitation(
                query = "Highlights from chapter VI",
                retrieved = listOf(hintChunk()),
            ),
        )
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 400,
                retrieved = listOf(hintChunk()),
                query = "Highlights from chapter VI",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `mixed mode with excerpts may cite`() {
        assertTrue(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.MIXED,
                ragBlockChars = 600,
                retrieved = listOf(
                    bodyChunk(score = 5.0).copy(
                        text = "--- Page 17 ---\nPenalties under the act",
                    ),
                ),
                query = "Penalties in the act and explain black holes",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `no sources when only outline on narrow query`() {
        assertFalse(
            shouldAttachDeterministicSources(
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                ragBlockChars = 400,
                retrieved = listOf(
                    RetrievedChunk(
                        text = "Document outline (auto-detected headings):\n- CHAPTER I",
                        docName = "act.pdf",
                        score = 1.0,
                        chunkIndex = OUTLINE_CHUNK_INDEX,
                        docUri = "content://act",
                    ),
                ),
                query = "What are penalties in the act",
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `citable chunks filter synthetic hints`() {
        val filtered = citableRetrievalChunks(
            listOf(
                hintChunk(),
                RetrievedChunk(
                    text = "registry",
                    docName = "act.pdf",
                    score = 1.0,
                    chunkIndex = STRUCTURE_REGISTRY_CHUNK_INDEX,
                    docUri = "content://act",
                ),
                bodyChunk(),
            ),
        )
        assertEquals(1, filtered.size)
        assertTrue(filtered[0].chunkIndex >= 0)
    }
}
