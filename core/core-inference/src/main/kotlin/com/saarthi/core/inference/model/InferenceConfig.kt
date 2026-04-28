package com.saarthi.core.inference.model

data class InferenceConfig(
    val modelPath: String,
    val modelName: String? = null,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val maxTokens: Int = 1024,
    val loraAdapterPath: String? = null,
    // llama.cpp specific (ignored by MediaPipe engine)
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    val nGpuLayers: Int = 0,
)
