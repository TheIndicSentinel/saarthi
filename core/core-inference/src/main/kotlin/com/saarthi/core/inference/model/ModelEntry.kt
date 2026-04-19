package com.saarthi.core.inference.model

enum class EngineType { MEDIAPIPE, LLAMA_CPP }

data class ModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    val engineType: EngineType,
    val requiredTier: DeviceTier,
    /** Short family key used to match LoRA adapters, e.g. "qwen2.5", "llama3.2", "gemma2". */
    val modelFamily: String,
    val nGpuLayers: Int = 0,
    val contextLength: Int = 2048,
    val tags: List<String> = emptyList(),
) {
    val fileSizeMb: Int get() = (fileSizeBytes / 1_048_576).toInt()
    val fileName: String get() = downloadUrl.substringAfterLast('/')
}
