package com.saarthi.core.inference.model

data class InferenceConfig(
    val modelPath: String,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val maxTokens: Int = 1024,
    val loraAdapterPath: String? = null,  // null = base model
)
