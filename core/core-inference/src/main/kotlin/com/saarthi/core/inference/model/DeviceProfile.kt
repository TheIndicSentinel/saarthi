package com.saarthi.core.inference.model

/**
 * A snapshot of the device's hardware state at the time of measurement.
 *
 * Unlike a static hardware spec sheet, this profile captures *runtime* values:
 * actual free RAM, real available storage, and GPU capability flags. This enables
 * the engine to dynamically adapt to how much of the device is already in use.
 */
data class DeviceProfile(
    // ── Memory ────────────────────────────────────────────────────────────────
    val totalRamMb: Long,
    /** RAM currently free (not in use by system, other apps, or the GPU). */
    val availableRamMb: Long,
    /**
     * RAM that is safe to budget for the AI model. Calculated as:
     * min(availableRam * 0.75, totalRam * 0.6) to preserve OS headroom.
     */
    val safeModelBudgetMb: Long,
    val availableStorageMb: Long,

    // ── CPU ───────────────────────────────────────────────────────────────────
    val cpuCores: Int,
    /** Threads recommended for inference. Avoids starving UI/OS threads. */
    val recommendedThreads: Int,

    // ── GPU / Accelerator ─────────────────────────────────────────────────────
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    /**
     * Whether it is safe to use GPU/Vulkan-backed acceleration for this session.
     * Takes into account: platform stability, available RAM (GPU shares LPDDR),
     * and whether the model binary fits within the GPU memory budget.
     */
    val gpuSafe: Boolean,

    // ── Platform ──────────────────────────────────────────────────────────────
    val abi: String,
    val apiLevel: Int,
    val manufacturer: String,
) {
    /**
     * Stable tier derived from the *safe budget*, not total RAM.
     * A device with 12GB total but 2GB free should not be treated as FLAGSHIP
     * for inference purposes.
     */
    val tier: DeviceTier get() = when {
        safeModelBudgetMb >= 5_500 -> DeviceTier.FLAGSHIP   // Can run 4B models comfortably
        safeModelBudgetMb >= 2_500 -> DeviceTier.MID        // Handles 2B models
        safeModelBudgetMb >= 1_000 -> DeviceTier.LOW        // 1B models only
        else                       -> DeviceTier.MINIMAL    // Extremely constrained
    }

    /** Maximum model file size (bytes) that we can safely attempt to load. */
    val maxSafeModelBytes: Long get() = safeModelBudgetMb * 1_048_576L

    override fun toString(): String =
        "DeviceProfile(tier=$tier, totalRam=${totalRamMb}MB, avail=${availableRamMb}MB, " +
        "budget=${safeModelBudgetMb}MB, threads=$recommendedThreads, gpu=$gpuSafe)"
}

enum class DeviceTier { MINIMAL, LOW, MID, FLAGSHIP }
