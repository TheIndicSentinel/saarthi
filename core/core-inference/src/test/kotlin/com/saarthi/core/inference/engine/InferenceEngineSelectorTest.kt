package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * InferenceEngineSelector is the routing layer between the app and the native
 * LiteRT engine. Its path-validation logic determines whether a model file can
 * be loaded at all — a wrong path routed to the engine causes a native crash
 * that is hard to debug after the fact.
 *
 * Two invariants under test:
 *  • Valid extensions (.litertlm, .task, .bin) → delegate to liteRtEngine.
 *  • Unsupported formats or /proc/self/fd/ paths → throw before touching the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceEngineSelectorTest {

    private lateinit var liteRtEngine: LiteRTInferenceEngine
    private lateinit var selector: InferenceEngineSelector

    @Before
    fun setUp() {
        liteRtEngine = mockk(relaxed = true)
        selector = InferenceEngineSelector(liteRtEngine)
    }

    // ── Valid paths — must delegate ────────────────────────────────────────────

    @Test
    fun `litertlm path is valid and delegates to engine`() = runTest {
        val config = InferenceConfig(modelPath = "/data/models/gemma-4-E2B-it.litertlm")
        coJustRun { liteRtEngine.initialize(config) }

        selector.initialize(config)

        coVerify(exactly = 1) { liteRtEngine.initialize(config) }
    }

    @Test
    fun `task extension is valid and delegates to engine`() = runTest {
        val config = InferenceConfig(modelPath = "/data/models/model.task")
        coJustRun { liteRtEngine.initialize(config) }

        selector.initialize(config)

        coVerify(exactly = 1) { liteRtEngine.initialize(config) }
    }

    @Test
    fun `bin extension is valid and delegates to engine`() = runTest {
        val config = InferenceConfig(modelPath = "/data/models/model.bin")
        coJustRun { liteRtEngine.initialize(config) }

        selector.initialize(config)

        coVerify(exactly = 1) { liteRtEngine.initialize(config) }
    }

    @Test
    fun `extension check is case-insensitive`() = runTest {
        val config = InferenceConfig(modelPath = "/data/models/gemma.LITERTLM")
        coJustRun { liteRtEngine.initialize(config) }

        selector.initialize(config)

        coVerify(exactly = 1) { liteRtEngine.initialize(config) }
    }

    // ── Invalid paths — must throw WITHOUT touching the engine ─────────────────

    @Test
    fun `fd path throws IllegalArgumentException before calling engine`() = runTest {
        val config = InferenceConfig(modelPath = "/proc/self/fd/42")

        assertFailsWith<IllegalArgumentException> {
            selector.initialize(config)
        }

        coVerify(exactly = 0) { liteRtEngine.initialize(any()) }
    }

    @Test
    fun `unsupported gguf extension throws UnsupportedOperationException`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/Download/model.gguf")

        assertFailsWith<UnsupportedOperationException> {
            selector.initialize(config)
        }

        coVerify(exactly = 0) { liteRtEngine.initialize(any()) }
    }

    @Test
    fun `unsupported ggml extension throws UnsupportedOperationException`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/Download/model.ggml")

        assertFailsWith<UnsupportedOperationException> {
            selector.initialize(config)
        }

        coVerify(exactly = 0) { liteRtEngine.initialize(any()) }
    }

    // ── Message quality ────────────────────────────────────────────────────────

    @Test
    fun `fd path error message mentions models folder`() = runTest {
        val config = InferenceConfig(modelPath = "/proc/self/fd/5")

        val ex = assertFailsWith<IllegalArgumentException> {
            selector.initialize(config)
        }

        assertTrue("Error must mention the models folder",
            ex.message?.contains("models folder", ignoreCase = true) == true)
    }

    @Test
    fun `unsupported format error message includes the extension`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/model.safetensors")

        val ex = assertFailsWith<UnsupportedOperationException> {
            selector.initialize(config)
        }

        assertTrue("Error must include the unknown extension",
            ex.message?.contains("safetensors", ignoreCase = true) == true)
    }
}
