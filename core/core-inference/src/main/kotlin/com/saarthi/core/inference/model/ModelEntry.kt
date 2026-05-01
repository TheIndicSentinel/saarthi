package com.saarthi.core.inference.model

/**
 * High-level SoC classification used to select the optimal model file.
 * Qualcomm devices have device-specific LiteRT-LM bundles that use the
 * QNN (Qualcomm Neural Networks) / Hexagon NPU delegate for best performance.
 */
enum class SocFamily {
    QUALCOMM_SM8750,  // Snapdragon 8 Gen 3+ — has QNN-optimized model files
    QUALCOMM_SM8550,  // Snapdragon 8 Gen 2  — use generic (no SM8550-specific file yet)
    QUALCOMM_GENERIC, // Other Snapdragon     — use generic file
    GOOGLE_TENSOR,    // Pixel devices        — use generic file
    SAMSUNG_EXYNOS,   // Exynos variants      — use generic file
    MEDIATEK,         // Dimensity, etc.      — use generic file
    GENERIC,          // Unknown / other      — use generic file
}

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
    /** Which SoC family this model file is compiled for. GENERIC = works on all devices. */
    val socTarget: SocFamily = SocFamily.GENERIC,
    /** ID of the generic base model this is a device-specific variant of. Empty = is the base. */
    val baseModelId: String = "",
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
