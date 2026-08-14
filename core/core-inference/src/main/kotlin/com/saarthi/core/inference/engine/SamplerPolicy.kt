package com.saarthi.core.inference.engine

import com.google.ai.edge.litertlm.SamplerConfig
import com.saarthi.core.inference.GenerationPreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three numbers a [SamplerConfig] is built from — a plain Kotlin type
 * with no litertlm dependency, so the actual decision logic in
 * [SamplerPolicy] is unit-testable without loading a single litertlm class.
 *
 * This split exists because `com.google.ai.edge.litertlm.SamplerConfig` is
 * compiled for Java 21 (class file version 65) — this project's CI
 * (.github/workflows/build_apk.yml) and local Gradle both currently pin
 * JDK 17 (class file version 61 max), so any unit test that actually
 * *constructs* a real SamplerConfig fails with
 * `UnsupportedClassVersionError` before a single assertion runs, on
 * exactly the JDK this project's own CI uses. The decision math itself
 * (temperature-override precedence, the grounded-mode tuning constants)
 * has nothing to do with litertlm's class file version — only hastily
 * bundling it into the same return type does.
 */
data class SamplerParams(val topK: Int, val topP: Double, val temperature: Double)

/**
 * Sampler-config decisions — extracted verbatim out of
 * [LiteRTInferenceEngine] (same constants, same NPU short-circuit, same
 * temperature-override logic) as part of the C3 God-class reduction.
 * [LiteRTInferenceEngine] keeps thin wrappers (`samplerForActiveModel()`
 * etc.) that supply the engine-local state (`usingNpu`, `loadedTemperature`,
 * `loadedTopK`) this class doesn't own — this class itself holds no engine
 * state, only the [generationPreference] dependency the decisions read.
 */
@Singleton
class SamplerPolicy @Inject constructor(
    private val generationPreference: GenerationPreference,
) {
    /**
     * The decision, as plain numbers — see [SamplerParams]'s kdoc for why
     * this is split out from [samplerFor]. Null for NPU (QNN handles
     * sampling internally on Hexagon). [temperature]/[topK] are the
     * model's catalog-provided defaults (ModelEntry.defaultTemperature/
     * topK) — overridden by the user's Settings → Response style
     * temperature slider when set; AUTO (-1) defers to the model default,
     * so users who never touch the slider keep the prior behaviour.
     * topK/topP stay model-tuned — only the temperature is user-facing.
     */
    fun samplerParamsFor(usingNpu: Boolean, temperature: Float, topK: Int): SamplerParams? {
        if (usingNpu) return null
        val userTemp = generationPreference.temperature.value
        val temp = (if (userTemp >= 0f) userTemp else temperature).toDouble()
        return SamplerParams(topK = topK, topP = 0.95, temperature = temp)
    }

    /** Wraps [samplerParamsFor] into the real litertlm type production code needs. */
    fun samplerFor(usingNpu: Boolean, temperature: Float, topK: Int): SamplerConfig? =
        samplerParamsFor(usingNpu, temperature, topK)?.let {
            SamplerConfig(topK = it.topK, topP = it.topP, temperature = it.temperature)
        }

    /**
     * The grounded-mode decision, as plain numbers — see [samplerParamsFor].
     *
     * Why a separate sampler for RAG mode:
     *  • The default Gemma 3/4 sampler (temp=1.0, topK=64, topP=0.95) is
     *    optimised for creative chat — it tolerates high-probability
     *    diversion. Inside RAG mode that diversion turns into the
     *    repetition loops we kept seeing ("[REP] Loop detected at 82
     *    tokens" on a production log).
     *  • Industry standard for document-grounded Q&A is temp ≈ 0.3–0.5
     *    with tighter top-p — pulls the model toward verbatim quotation
     *    of the cited excerpt, which is exactly what we want when we've
     *    already told it "answer ONLY from these excerpts".
     *  • NPU still returns null (Hexagon does sampling on-chip).
     */
    fun groundedSamplerParamsFor(usingNpu: Boolean): SamplerParams? {
        if (usingNpu) return null
        return SamplerParams(topK = 40, topP = 0.85, temperature = 0.4)
    }

    /** Wraps [groundedSamplerParamsFor] into the real litertlm type production code needs. */
    fun groundedSamplerFor(usingNpu: Boolean): SamplerConfig? =
        groundedSamplerParamsFor(usingNpu)?.let {
            SamplerConfig(topK = it.topK, topP = it.topP, temperature = it.temperature)
        }

    /**
     * Marker baked into ChatRepositoryImpl.buildRagPromptBlock for the
     * LARGE/STANDARD strict-mode block. Detecting this here keeps the
     * engine API unchanged — no new parameter on `generateStream()` — and
     * automatically swaps in [groundedSamplerFor] whenever RAG context is
     * present. False-positive risk is negligible: the literal phrase
     * "ATTACHED EXCERPTS" doesn't appear in normal chat content.
     */
    fun isGroundedPrompt(prompt: String): Boolean =
        prompt.contains("ATTACHED EXCERPTS")
}
