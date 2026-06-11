package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * InferenceEngineSelector is the routing layer between the app and the native
 * LiteRT engine. Its path-validation logic is the user-facing gate: a wrong
 * path must be rejected with a clear, actionable message BEFORE it ever reaches
 * the native engine (where it would otherwise cause a hard-to-debug crash).
 *
 * Scope of these tests: the validation / routing logic only. For every input
 * below the selector throws inside [InferenceEngineSelector.initialize] before
 * delegating, so no interaction with the (final, native-backed, un-mockable)
 * [LiteRTInferenceEngine] occurs — the relaxed mock is a constructor placeholder
 * that is never invoked. The happy-path delegation is a trivial pass-through and
 * is not covered here because exercising it would require loading the real
 * native engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceEngineSelectorTest {

    private lateinit var liteRtEngine: LiteRTInferenceEngine
    private lateinit var selector: InferenceEngineSelector

    @Before
    fun setUp() {
        // Placeholder dependency only — never invoked by the validation paths
        // under test (the selector throws before delegating).
        liteRtEngine = mockk(relaxed = true)
        selector = InferenceEngineSelector(liteRtEngine)
    }

    // ── /proc/self/fd/ paths that ARE litertlm models → IllegalArgumentException ─
    //
    // A URI-picked .litertlm resolved to a /proc/self/fd/ path cannot be stat()'d
    // by MediaPipe's native loader, so the selector rejects it early and tells
    // the user to download via the catalog instead.

    @Test
    fun `fd path to a litertlm model throws IllegalArgumentException`() = runTest {
        val config = InferenceConfig(modelPath = "/proc/self/fd/42.litertlm")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertTrue("Must throw IllegalArgumentException, got $ex",
            ex is IllegalArgumentException)
    }

    @Test
    fun `fd path error message mentions the models folder`() = runTest {
        val config = InferenceConfig(modelPath = "/proc/self/fd/5.litertlm")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertNotNull("Must throw for fd path", ex)
        assertTrue("Error must mention the models folder, got: ${ex?.message}",
            ex!!.message?.contains("models folder", ignoreCase = true) == true)
    }

    // ── Unsupported formats → UnsupportedOperationException ─────────────────────

    @Test
    fun `gguf extension throws UnsupportedOperationException`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/Download/model.gguf")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertTrue("Must throw UnsupportedOperationException, got $ex",
            ex is UnsupportedOperationException)
    }

    @Test
    fun `ggml extension throws UnsupportedOperationException`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/Download/model.ggml")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertTrue("Must throw UnsupportedOperationException, got $ex",
            ex is UnsupportedOperationException)
    }

    @Test
    fun `unsupported format error message includes the extension`() = runTest {
        val config = InferenceConfig(modelPath = "/sdcard/model.safetensors")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertNotNull("Must throw for unsupported format", ex)
        assertTrue("Error must include the unknown extension, got: ${ex?.message}",
            ex!!.message?.contains("safetensors", ignoreCase = true) == true)
    }

    // ── Edge case: bare fd path with NO extension ──────────────────────────────
    //
    // Documents the actual routing: a /proc/self/fd/N path WITHOUT a recognised
    // model extension does not match isLiteRTModel(), so it falls through to the
    // unsupported-format branch rather than the fd-specific message.

    @Test
    fun `bare fd path without extension is treated as unsupported format`() = runTest {
        val config = InferenceConfig(modelPath = "/proc/self/fd/42")

        val ex = runCatching { selector.initialize(config) }.exceptionOrNull()

        assertTrue("Must throw UnsupportedOperationException, got $ex",
            ex is UnsupportedOperationException)
    }
}
