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
    /**
     * Android's own OEM-influenced classification (`ActivityManager.
     * isLowRamDevice()`) of whether this device model is memory-constrained.
     * A coarse, largely static hardware signal — NOT a substitute for
     * [availableRamMb], which is what actually decides memory eligibility at
     * load time. Used only as an additional conservative input (e.g.
     * widening which models get gated to CPU on this device), never as the
     * primary or sole basis for a decision.
     */
    val isLowRamDevice: Boolean = false,

    // ── CPU ───────────────────────────────────────────────────────────────────
    val cpuCores: Int,
    /** Threads recommended for inference. Avoids starving UI/OS threads. */
    val recommendedThreads: Int,

    // ── GPU / Accelerator ─────────────────────────────────────────────────────
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    /**
     * Whether this device's Vulkan support and SoC/OEM driver history make
     * GPU acceleration worth attempting at all — a static hardware fact,
     * independent of which model gets loaded. Whether there's enough RAM
     * for a *specific* model's GPU footprint is a separate, load-time
     * decision made by the engine once the model is known (see
     * LiteRTInferenceEngine's memory-pressure and tier-restriction gates).
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
