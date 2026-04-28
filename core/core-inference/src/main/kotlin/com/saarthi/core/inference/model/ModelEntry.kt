package com.saarthi.core.inference.model

enum class EngineType {
    /** MediaPipe LiteRT (.task / .litertlm) — GPU-accelerated, Google official. */
    LITERT,
    /** llama.cpp GGUF — CPU fallback for community/custom models. */
    LLAMA_CPP,
    /** Kept for source compatibility; treated as LITERT at runtime. */
    MEDIAPIPE,
}

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

    /**
     * Checks if this model is safe to load based on the current [DeviceProfile].
     * Account for the model size plus a fixed 300MB overhead for the KV-cache and UI.
     */
    fun isSafeFor(profile: DeviceProfile): Boolean {
        val requiredMb = fileSizeMb + 300
        return requiredMb <= profile.safeModelBudgetMb
    }
}
