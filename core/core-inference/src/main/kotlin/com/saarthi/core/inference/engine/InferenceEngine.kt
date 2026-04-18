package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow

// Single Responsibility: only responsible for text generation
// Open/Closed: new backends (llama.cpp, etc.) implement this without modifying callers
interface InferenceEngine {
    val isReady: Boolean

    suspend fun initialize(config: InferenceConfig)

    // Streams partial tokens as they are generated
    fun generateStream(prompt: String, packType: PackType = PackType.BASE): Flow<String>

    // One-shot generation (waits for full response)
    suspend fun generate(prompt: String, packType: PackType = PackType.BASE): String

    fun release()
}
