package com.saarthi.core.inference.model

/**
 * Inputs to [com.saarthi.core.inference.engine.InferenceEngine.initialize].
 *
 * - [maxTokens] = 0 lets the engine pick a tier-aware default (Gemma 4 → 2048,
 *   Gemma 3n / 2 → 1024, Gemma 3 1B / Compact → 512). Pass a non-zero value
 *   only to force-override during testing or device-specific tuning.
 * - [nThreads] is consumed by the CPU backend; the engine ignores it for
 *   GPU / NPU paths.
 *
 * The previous LoRA-adapter and llama.cpp-context fields (loraAdapterPath,
 * nCtx, nGpuLayers) were removed in v1.0.19 along with the unused native
 * bridge — see PackAdapterManager / cpp/llama.cpp deletion in that commit.
 */
data class InferenceConfig(
    val modelPath: String,
    val modelName: String? = null,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val maxTokens: Int = 0,
    val nThreads: Int = 4,
)
