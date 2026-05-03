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
    /**
     * Whether it is safe to use the NPU (Qualcomm QNN/Hexagon) backend.
     * Only true on SM8750 (Snapdragon 8 Gen 3) where device-specific
     * QNN-compiled .litertlm bundles exist. All other SoCs use GPU/CPU.
     */
    val npuSafe: Boolean = false,

    // ── Platform ──────────────────────────────────────────────────────────────
    val abi: String,
    val apiLevel: Int,
    val manufacturer: String,

    // ── SoC ───────────────────────────────────────────────────────────────────
    /** Raw SoC model string from Build.SOC_MODEL (e.g. "SM8550", "kalama"). */
    val socModel: String = "",
    /** Classified SoC family for device-specific model file selection. */
    val socFamily: SocFamily = SocFamily.GENERIC,
) {
    /**
     * Stable device tier derived from TOTAL RAM — not runtime available RAM.
     *
     * Available RAM fluctuates constantly (background apps, kernel caches) and
     * using it for tier would flip a 12GB phone between MID and FLAGSHIP on every
     * app launch. Total RAM is a fixed hardware spec that never changes.
     *
     * The runtime [safeModelBudgetMb] is still used for the actual load-safety
     * check — this tier is only for model *compatibility* filtering in the catalog.
     */
    val tier: DeviceTier get() = when {
        totalRamMb >= 10_000 -> DeviceTier.FLAGSHIP  // 10GB+ (S23 Ultra, Pixel 8 Pro, etc.)
        totalRamMb >= 6_000  -> DeviceTier.MID       // 6-10GB (A54, Pixel 7a, etc.)
        totalRamMb >= 3_500  -> DeviceTier.LOW       // 3.5-6GB (budget phones)
        else                 -> DeviceTier.MINIMAL   // <3.5GB (ultra-budget)
    }

    /** Maximum model file size (bytes) that we can safely attempt to load. */
    val maxSafeModelBytes: Long get() = safeModelBudgetMb * 1_048_576L

    override fun toString(): String =
        "DeviceProfile(tier=$tier, totalRam=${totalRamMb}MB, avail=${availableRamMb}MB, " +
        "budget=${safeModelBudgetMb}MB, threads=$recommendedThreads, gpu=$gpuSafe, npu=$npuSafe, " +
        "soc=$socModel/$socFamily)"

}

enum class DeviceTier { MINIMAL, LOW, MID, FLAGSHIP }
