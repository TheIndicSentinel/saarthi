package com.saarthi.core.inference.engine

import com.saarthi.core.inference.GenerationPreference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SamplerPolicy] is real decision logic (the RAG-repetition-loop fix, the
 * user-temperature-override contract) extracted out of
 * [LiteRTInferenceEngine] as part of the C3 God-class reduction — the
 * first sampler-policy code in this project to get direct test coverage
 * rather than being exercised only implicitly through generation.
 *
 * Deliberately tests [SamplerPolicy.samplerParamsFor] /
 * [SamplerPolicy.groundedSamplerParamsFor] (returning the plain
 * [SamplerParams] type), NOT [SamplerPolicy.samplerFor] /
 * [SamplerPolicy.groundedSamplerFor] directly — those wrap the result into
 * a real `com.google.ai.edge.litertlm.SamplerConfig`, a class compiled for
 * Java 21 (class file version 65). This project's CI
 * (.github/workflows/build_apk.yml) and this session's local Gradle both
 * pin/run JDK 17 (class file version 61 max); a unit test that actually
 * constructs a SamplerConfig fails with UnsupportedClassVersionError
 * before any assertion runs, unrelated to whether the logic is correct.
 * Testing the *ParamsFor functions gets full coverage of the actual
 * decision math (which is what's under test) without touching litertlm's
 * class files at all — see [SamplerParams]'s kdoc for the full reasoning.
 * The thin `?.let { SamplerConfig(...) }` wrap in samplerFor/
 * groundedSamplerFor is a one-line, low-risk pass-through left uncovered
 * by unit tests for that reason, same as other native-type boundaries
 * elsewhere in this module.
 */
class SamplerPolicyTest {

    private fun policy(userTemp: Float = -1f): SamplerPolicy {
        val prefs = mockk<GenerationPreference>()
        every { prefs.temperature } returns MutableStateFlow(userTemp)
        return SamplerPolicy(prefs)
    }

    @Test
    fun `NPU backend returns null sampler params — QNN handles sampling on-chip`() {
        assertNull(policy().samplerParamsFor(usingNpu = true, temperature = 1.0f, topK = 64))
        assertNull(policy().groundedSamplerParamsFor(usingNpu = true))
    }

    @Test
    fun `AUTO (-1) user temperature defers to the model's catalog default`() {
        val params = policy(userTemp = -1f).samplerParamsFor(usingNpu = false, temperature = 0.7f, topK = 64)
        assertEquals(0.7, params!!.temperature, 0.0001)
    }

    @Test
    fun `a real user temperature override replaces the model default`() {
        val params = policy(userTemp = 0.3f).samplerParamsFor(usingNpu = false, temperature = 1.0f, topK = 64)
        assertEquals(0.3, params!!.temperature, 0.0001)
    }

    @Test
    fun `user temperature override does not touch topK or topP — only temperature is user-facing`() {
        val params = policy(userTemp = 0.5f).samplerParamsFor(usingNpu = false, temperature = 1.0f, topK = 40)
        assertEquals(40, params!!.topK)
        assertEquals(0.95, params.topP, 0.0001)
    }

    @Test
    fun `grounded sampler uses fixed tight-quotation tuning, not the model or user temperature`() {
        // Deliberately ignores userTemp/topK — RAG mode always gets the
        // same tuned values, independent of the chat sampler's state.
        val params = policy(userTemp = 0.9f).groundedSamplerParamsFor(usingNpu = false)
        assertEquals(0.4, params!!.temperature, 0.0001)
        assertEquals(40, params.topK)
        assertEquals(0.85, params.topP, 0.0001)
    }

    @Test
    fun `isGroundedPrompt detects the RAG strict-mode marker`() {
        assertTrue(policy().isGroundedPrompt("... ATTACHED EXCERPTS ..."))
        assertFalse(policy().isGroundedPrompt("just a normal chat message"))
    }
}
