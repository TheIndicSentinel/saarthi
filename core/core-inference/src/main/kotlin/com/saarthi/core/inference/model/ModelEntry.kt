package com.saarthi.core.inference.model

/**
 * High-level SoC classification used to select the optimal model file.
 * Qualcomm devices have device-specific LiteRT-LM bundles that use the
 * QNN (Qualcomm Neural Networks) / Hexagon NPU delegate for best performance.
 */
enum class SocFamily {
    QUALCOMM_SM8750,  // Snapdragon 8 Gen 3+ — has QNN-optimized model files (HTP v75/v79)
    QUALCOMM_SM8550,  // Snapdragon 8 Gen 2  — uses generic files (HTP v69 incompatible with litertlm 0.10.2 QNN)
    QUALCOMM_GENERIC, // Other Snapdragon     — use generic file
    GOOGLE_TENSOR,    // Pixel devices        — use generic file
    SAMSUNG_EXYNOS,   // Exynos variants      — use generic file
    MEDIATEK,         // Dimensity, etc.      — use generic file
    GENERIC,          // Unknown / other      — use generic file
}

enum class EngineType {
    /** Google AI Edge LiteRT-LM (.task / .litertlm) — the only engine we ship. */
    LITERT,
}

data class ModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    val engineType: EngineType,
    val requiredTier: DeviceTier,
    val contextLength: Int = 2048,
    val tags: List<String> = emptyList(),
    /** Which SoC family this model file is compiled for. GENERIC = works on all devices. */
    val socTarget: SocFamily = SocFamily.GENERIC,
    /** ID of the generic base model this is a device-specific variant of. Empty = is the base. */
    val baseModelId: String = "",
    /**
     * Expected lowercase-hex SHA-256 of the downloaded file, verified once
     * right after a fresh download completes (see [ModelDownloadService]).
     * Null = no verification (today's behavior — size-threshold only),
     * so existing catalog entries and already-downloaded files on users'
     * devices are unaffected. Only meaningful when [downloadUrl] is pinned
     * to an immutable commit revision, not a mutable ref like `resolve/main`
     * — otherwise the expected hash would go stale the moment upstream
     * changes and every future download would fail verification.
     */
    val expectedSha256: String? = null,
) {

    val fileSizeMb: Int get() = (fileSizeBytes / 1_048_576).toInt()
    val fileName: String get() = downloadUrl.substringAfterLast('/')

    /**
     * Returns true when this model is safe to offer to a device.
     *
     * Two gates must both pass:
     *
     * 1. **Tier gate (total-RAM based, stable):**
     *    [DeviceProfile.tier] is derived from total RAM — a fixed hardware spec
     *    that never changes. It is the correct signal for deciding which models
     *    a device class can handle. The previous gate used [DeviceProfile.safeModelBudgetMb]
     *    which is 75% of *available* RAM — a number that swings by ±1 GB in a
     *    single session (from 1459 MB to 3044 MB on the same OnePlus CPH2487 in
     *    one test run), causing models to silently appear/disappear between launches.
     *    LiteRT already memory-maps weights from flash storage; the whole file
     *    does not need to be RAM-resident. The tier gate correctly captures
     *    whether the device *class* can handle this model, not whether it
     *    happens to have enough free RAM at this millisecond.
     *
     * 2. **Storage gate:**
     *    The model must physically fit on the device's storage. Buffer is the
     *    larger of 500 MB or 5% of the model's own size — a flat 500 MB alone
     *    doesn't absorb the confirmed HuggingFace file-size drift (a live
     *    field download completed at 2468 MB against a 2463 MB catalog
     *    estimate); scaling with the model's size keeps margin proportional
     *    for the larger (multi-GB) catalog entries. This is a cheap,
     *    StatFs-based picker-list filter — the authoritative check right
     *    before a download actually starts is [ModelDownloadManager]'s
     *    StorageManager-based pre-flight gate.
     */
    fun isSafeFor(profile: DeviceProfile): Boolean {
        // Gate 1: device tier (total-RAM based — stable)
        if (profile.tier.ordinal < requiredTier.ordinal) return false
        // Gate 2: enough storage to download
        val bufferMb = maxOf(500, fileSizeMb / 20)
        if (profile.availableStorageMb < fileSizeMb + bufferMb) return false
        return true
    }
}
