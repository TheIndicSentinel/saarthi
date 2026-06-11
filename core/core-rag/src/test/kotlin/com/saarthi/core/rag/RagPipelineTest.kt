package com.saarthi.core.rag

import com.saarthi.core.rag.embedding.EmbeddingModel
import com.saarthi.core.rag.vectorstore.SearchResult
import com.saarthi.core.rag.vectorstore.VectorStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RagPipeline glues the embedding model + vector store into the chat
 * prompt. Two pieces matter:
 *
 *   • buildAugmentedPrompt — when the store has no relevant chunks the
 *     pipeline must NOT inject empty context (would degrade prompt
 *     quality with a "Context (use ONLY this to answer):" header
 *     followed by nothing). When it does have matches, the context must
 *     be wrapped in the strict-grounding header.
 *
 *   • indexDocument — chunks long text. The chunk math (size 512, overlap 64)
 *     determines how many embeddings + how much storage we burn per indexed
 *     document. Off-by-one in chunkText silently doubles vector-store size.
 *
 * Both EmbeddingModel and VectorStore are mocked at the interface level so
 * these tests run as pure JVM unit tests — no SQLite, no model file.
 */
class RagPipelineTest {

    private lateinit var embeddingModel: EmbeddingModel
    private lateinit var vectorStore: VectorStore
    private lateinit var pipeline: RagPipeline

    private val fakeEmbedding = floatArrayOf(0.1f, 0.2f, 0.3f)

    @Before
    fun setUp() {
        embeddingModel = mockk(relaxed = true)
        vectorStore = mockk(relaxed = true)
        coEvery { embeddingModel.embed(any()) } returns fakeEmbedding
        pipeline = RagPipeline(embeddingModel, vectorStore)
    }

    // ── buildAugmentedPrompt ────────────────────────────────────────────

    @Test
    fun `buildAugmentedPrompt returns base prompt when store is empty`() = runTest {
        coEvery { vectorStore.search(any(), any()) } returns emptyList()

        val basePrompt = "Answer the user's question."
        val result = pipeline.buildAugmentedPrompt("any query", basePrompt)

        // No retrieved chunks → return basePrompt verbatim. Critical:
        // we do NOT prepend an empty "Context:" block, which would
        // confuse the model into refusing to answer.
        assertEquals(basePrompt, result)
    }

    @Test
    fun `buildAugmentedPrompt wraps retrieved chunks in strict-grounding header`() = runTest {
        coEvery { vectorStore.search(any(), any()) } returns listOf(
            SearchResult(id = 1, text = "The capital of India is New Delhi.", score = 0.9f),
            SearchResult(id = 2, text = "Mumbai is the financial capital.", score = 0.8f),
        )

        val basePrompt = "User asks: What is India's capital?"
        val result = pipeline.buildAugmentedPrompt("capital", basePrompt)

        // The "use ONLY this to answer" header is the contract that
        // tells the model these chunks are ground truth. Removing it
        // turns RAG into "context as a hint" which silently regresses
        // factual accuracy.
        assertTrue("Must contain strict-grounding header. Got:\n$result",
            result.contains("Context (use ONLY this to answer):"))
        assertTrue("Must contain first chunk", result.contains("The capital of India is New Delhi."))
        assertTrue("Must contain second chunk", result.contains("Mumbai is the financial capital."))
        assertTrue("Must keep base prompt", result.contains(basePrompt))
    }

    @Test
    fun `buildAugmentedPrompt joins multiple chunks with separator`() = runTest {
        coEvery { vectorStore.search(any(), any()) } returns listOf(
            SearchResult(id = 1, text = "Chunk A", score = 0.9f),
            SearchResult(id = 2, text = "Chunk B", score = 0.8f),
        )

        val result = pipeline.buildAugmentedPrompt("q", "p")

        // Separator "---" lets the model parse chunk boundaries without
        // confusing them as continuation of the same passage.
        val sepIdx = result.indexOf("---")
        assertTrue("Must use --- separator between chunks. Got:\n$result", sepIdx > 0)
        assertTrue("Chunk A before separator", result.indexOf("Chunk A") < sepIdx)
        assertTrue("Chunk B after separator", result.indexOf("Chunk B") > sepIdx)
    }

    @Test
    fun `buildAugmentedPrompt embeds the user query exactly once`() = runTest {
        coEvery { vectorStore.search(any(), any()) } returns emptyList()

        pipeline.buildAugmentedPrompt("How do I save tax?", "base")

        coVerify(exactly = 1) { embeddingModel.embed("How do I save tax?") }
    }

    @Test
    fun `buildAugmentedPrompt requests topK equals 3`() = runTest {
        coEvery { vectorStore.search(any(), any()) } returns emptyList()

        pipeline.buildAugmentedPrompt("q", "p")

        // topK=3 is the contract — bumping it to 5 is "free" until you
        // realise the model's context budget shrinks proportionally.
        coVerify { vectorStore.search(any(), 3) }
    }

    // ── indexDocument ───────────────────────────────────────────────────

    @Test
    fun `indexDocument inserts a single chunk for a short document`() = runTest {
        // 10 words, chunkSize=512 → fits in one chunk.
        val short = (1..10).joinToString(" ") { "word$it" }

        pipeline.indexDocument(short)

        coVerify(exactly = 1) { vectorStore.insert(any(), any()) }
    }

    @Test
    fun `indexDocument splits a long document into overlapping chunks`() = runTest {
        // 1100 words. chunkSize=512, overlap=64 means each chunk advances
        // 448 words. Expected chunks:
        //   chunk 1: 0..511     (512 words)
        //   chunk 2: 448..959   (512 words)
        //   chunk 3: 896..1099  (204 words)
        // → exactly 3 chunks, with overlap visible between consecutive ones.
        val long = (1..1100).joinToString(" ") { "w$it" }
        val capturedChunks = mutableListOf<String>()
        coEvery { vectorStore.insert(any(), any()) } answers {
            capturedChunks += firstArg<String>()
            capturedChunks.size.toLong()
        }

        pipeline.indexDocument(long)

        assertEquals("Long doc must split into exactly 3 chunks", 3, capturedChunks.size)

        // First chunk starts at w1, second at w449 (overlap of 64 with first).
        assertTrue("First chunk should start with w1", capturedChunks[0].startsWith("w1 "))
        assertTrue("Second chunk should start with w449 (overlap)",
            capturedChunks[1].startsWith("w449 "))

        // Overlap invariant: last 64 words of chunk N == first 64 words of chunk N+1.
        val chunk0Tail = capturedChunks[0].split(" ").takeLast(64)
        val chunk1Head = capturedChunks[1].split(" ").take(64)
        assertEquals("64-word overlap between consecutive chunks", chunk0Tail, chunk1Head)
    }

    @Test
    fun `indexDocument embeds each chunk before storing it`() = runTest {
        val text = (1..1100).joinToString(" ") { "w$it" }

        pipeline.indexDocument(text)

        // One embed call per chunk — verifies we don't accidentally
        // re-embed the whole document or skip embedding entirely.
        coVerify(exactly = 3) { embeddingModel.embed(any()) }
        coVerify(exactly = 3) { vectorStore.insert(any(), any()) }
    }

    @Test
    fun `indexDocument handles a document smaller than overlap correctly`() = runTest {
        // Edge case: text shorter than the overlap (64 words). The chunker
        // must still produce exactly one chunk and not loop forever.
        val tiny = (1..3).joinToString(" ") { "w$it" }

        pipeline.indexDocument(tiny)

        coVerify(exactly = 1) { vectorStore.insert(any(), any()) }
    }

    @Test
    fun `indexDocument does not insert when text is empty`() = runTest {
        // Whitespace-only / empty input must NOT poison the store with a chunk
        // of nothing. indexDocument() guards this with `if (text.isBlank()) return`,
        // so no embedding is computed and nothing is inserted. (An earlier
        // version of this test locked in the old buggy behaviour of inserting a
        // single empty chunk; the guard is now in place, so we assert zero.)
        val capturedChunks = mutableListOf<String>()
        coEvery { vectorStore.insert(any(), any()) } answers {
            capturedChunks += firstArg<String>()
            capturedChunks.size.toLong()
        }

        pipeline.indexDocument("")

        assertEquals("Blank input must insert nothing", 0, capturedChunks.size)
    }

    @Test
    fun `indexDocument never produces zero chunks for non-empty text`() = runTest {
        val capturedChunks = mutableListOf<String>()
        coEvery { vectorStore.insert(any(), any()) } answers {
            capturedChunks += firstArg<String>()
            capturedChunks.size.toLong()
        }

        pipeline.indexDocument("hello world")

        assertFalse("Non-empty input must produce at least one chunk",
            capturedChunks.isEmpty())
    }
}
