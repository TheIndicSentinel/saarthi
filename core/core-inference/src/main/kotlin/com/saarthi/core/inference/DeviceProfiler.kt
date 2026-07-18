package com.saarthi.core.inference

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

import android.os.StatFs
import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.SocFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime hardware profiler that measures the device's *actual* available
 * resources at the moment of query — not static spec-sheet values.
 *
 * Every call to [profile] returns a fresh snapshot. Key design principles:
 *
 * 1. **Available RAM over Total RAM**: A 12GB phone with 2GB free is LOW-tier
 *    for inference, not FLAGSHIP. We measure `availMem`, not `totalMem`.
 *
 * 2. **Safe Budget = 75% of available, capped at 60% of total**: This leaves
 *    headroom for the OS, GPU driver allocations, and background apps so the
 *    LMK (Low-Memory Killer) never targets our process.
 *
 * 3. **Thread count = cores − 2, clamped [2, 4]**: Leaves 2 cores for the
 *    Android UI thread + system daemons. Going above 4 threads hits
 *    diminishing returns on ARM big.LITTLE and causes thermal throttling.
 *
 * 4. **GPU safety is SoC-aware, not model-aware**: Checks Vulkan support and
 *    OEM driver stability history only — static hardware facts. Whether
 *    there's enough RAM for a *specific* model's GPU footprint is decided
 *    at load time by the engine, once the model (and its resident-memory
 *    estimate) is actually known.
 *
 * 5. **NPU safety**: Only Qualcomm SM8750 (Snapdragon 8 Gen 3) currently has
 *    QNN/Hexagon-compiled .litertlm bundles. All other SoCs use GPU or CPU.
 */
@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Take a fresh snapshot of the device's hardware state.
     * Call this just before engine init — not at app startup — so the
     * measurements reflect the actual memory pressure at inference time.
     */
    fun profile(): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // OEM-influenced, largely static per device model — an additional
        // conservative signal for the engine's load-time gates, not a
        // replacement for the availMem reading above.
        val isLowRamDevice = am.isLowRamDevice

        val totalRamMb = memInfo.totalMem / 1_048_576
        val availRamMb = memInfo.availMem / 1_048_576

        // ── Safe Model Budget ────────────────────────────────────────────────
        // Rule (tier-aware): use up to N% of available RAM, but never more
        // than 60% of total RAM.
        //   • Flagship phones (10 GB+ total RAM) hold large amounts in
        //     reclaimable caches — 85% of `availMem` is genuinely
        //     available because the OS evicts caches on memory pressure.
        //     The previous 75% was too conservative and was hiding
        //     Gemma 4 E4B (3490 MB) from the picker on Galaxy S23+ class
        //     devices, forcing the user to manually clear RAM repeatedly.
        //   • Mid-tier keeps 75% (the previous default).
        //   • Low/minimal RAM devices stay at 65% so the OS isn't pushed
        //     into a thrash / LMK cycle by a borderline model.
        val availMultiplier = when {
            totalRamMb >= 10_000L -> 85
            totalRamMb >= 6_000L  -> 75
            else                  -> 65
        }
        val budgetFromAvail = (availRamMb * availMultiplier) / 100
        val budgetFromTotal = (totalRamMb * 60) / 100
        val safeModelBudgetMb = minOf(budgetFromAvail, budgetFromTotal)

        // ── Storage ──────────────────────────────────────────────────────────
        val storageDir = context.filesDir
        val availStorageMb = runCatching {
            val stat = StatFs(storageDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / 1_048_576
        }.getOrDefault(0L)

        // ── SoC Family Detection (must precede thread & GPU/NPU safety checks) ──
        val socModel = detectSocModel()
        val socFamily = classifySoc(socModel)

        // ── CPU Threads ──────────────────────────────────────────────────────
        val cpuCores = Runtime.getRuntime().availableProcessors()
        // Leave 2 cores for UI + OS. Min 2 threads (even on dual-core).
        // Max 4 threads (beyond this, ARM big.LITTLE thermal-throttles).
        val recommendedThreads = (cpuCores - 2).coerceIn(2, 4)

        // ── GPU / Vulkan ─────────────────────────────────────────────────────
        val hasVulkan = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val vulkanVersion = getVulkanVersion()

        val manufacturer = Build.MANUFACTURER.orEmpty()
        val apiLevel = Build.VERSION.SDK_INT

        // ── GPU Safety: SoC-aware backend policy ─────────────────────────────────
        //
        // Per-chip policy:
        //  QUALCOMM SM8750  — GPU always (Adreno 830, no known issues).
        //  QUALCOMM SM8550  — CPU only. The Adreno 740 driver crashes deeply in the native
        //                     OpenCL litertlm library during createConversation().
        //  QUALCOMM GENERIC — GPU enabled.
        //  GOOGLE TENSOR    — GPU always. Stable OpenCL on all API levels.
        //  SAMSUNG EXYNOS   — CPU on API 34+. OpenCL driver regression is OEM-level.
        //  MEDIATEK flagship — GPU on ≥8GB RAM (Mali OpenCL stable on Dimensity flagship).
        //  MEDIATEK other   — CPU. OpenCL driver quality too variable for production.
        //  GENERIC / unknown — GPU if 4GB+ avail RAM.
        //
        // Deliberately NOT a memory-eligibility check for a specific model —
        // gpuSafe answers "can this device's Vulkan/OEM driver run the GPU
        // delegate at all", a static hardware fact independent of which
        // model gets loaded. Whether there's enough headroom for THIS
        // model's GPU footprint is a load-time question (model size/
        // resident estimate + KV-cache aren't known here) — that's decided
        // by LiteRTInferenceEngine's memoryPressureBannedGpu gate, which
        // sizes its margin off the actual model instead of one number
        // guessed to cover the largest catalog entry. (This property used
        // to also veto on `availRamMb < 3_000` directly, which fired before
        // that model-aware check ever ran — a capable device loading a
        // small model could get vetoed by a floor sized for a much bigger
        // one. MediaTek/Generic below still factor in RAM, but as a
        // driver-confidence proxy for unproven chips, not a memory-headroom
        // check — left as-is.)
        val gpuSafe: Boolean = when {
            !hasVulkan -> false           // No Vulkan = no GPU delegate in LiteRT
            else -> when (socFamily) {
                SocFamily.QUALCOMM_SM8750  -> true
                SocFamily.QUALCOMM_SM8550  -> true // Dynamic retry allowed now that models are in internal storage
                SocFamily.QUALCOMM_GENERIC -> true
                SocFamily.GOOGLE_TENSOR    -> true
                SocFamily.SAMSUNG_EXYNOS   -> apiLevel < 34
                SocFamily.MEDIATEK         -> totalRamMb >= 8_000 && availRamMb >= 4_000
                SocFamily.GENERIC          -> availRamMb >= 4_000
            }
        }

        val gpuSafeReason = when {
            !hasVulkan         -> "no Vulkan support"
            socFamily == SocFamily.SAMSUNG_EXYNOS && apiLevel >= 34 ->
                "Exynos+API34+: OpenCL driver regression"
            socFamily == SocFamily.MEDIATEK && !(totalRamMb >= 8_000 && availRamMb >= 4_000) ->
                "MediaTek: insufficient RAM for reliable GPU"
            else -> if (gpuSafe) "OK" else "GENERIC: avail RAM < 4GB"
        }

        // ── NPU Safety: QNN/Hexagon backend policy ────────────────────────────
        //
        // NPU via litertlm-android Backend.NPU(nativeLibraryDir) uses Qualcomm QNN
        // (Qualcomm Neural Networks) to run inference on the Hexagon DSP/NPU.
        //
        // SM8750 (Snapdragon 8 Gen 3) — all Gemma4/3n/3 1B models (HTP v75/v79, supported)
        // SM8550 (Snapdragon 8 Gen 2) — DISABLED: litertlm QNN runtime targets HTP v75/v79;
        //   SM8550 uses HTP v69 → version mismatch → SIGKILL in Engine.initialize().
        //   Even though a sm8550-named bundle exists, the QNN libraries bundled in
        //   litertlm-android 0.10.2 are incompatible with SM8550's Hexagon firmware.
        //   Falls through to CPU (stable via XNNPACK).
        val npuSafe: Boolean = when {
            availRamMb < 3_000                     -> false
            socFamily == SocFamily.QUALCOMM_SM8750 -> true
            socFamily == SocFamily.QUALCOMM_SM8550 -> false  // HTP v69 mismatch — SIGKILL on NPU init
            else                                   -> false
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        DebugLogger.log("PROFILER",
            "GPU policy: gpuSafe=$gpuSafe  reason=$gpuSafeReason  " +
            "npuSafe=$npuSafe  soc=$socModel  api=$apiLevel  manufacturer=$manufacturer")

        val profile = DeviceProfile(
            totalRamMb        = totalRamMb,
            availableRamMb    = availRamMb,
            safeModelBudgetMb = safeModelBudgetMb,
            availableStorageMb = availStorageMb,
            cpuCores          = cpuCores,
            recommendedThreads = recommendedThreads,
            hasVulkan         = hasVulkan,
            vulkanVersion     = vulkanVersion,
            gpuSafe           = gpuSafe,
            npuSafe           = npuSafe,
            isLowRamDevice    = isLowRamDevice,
            abi               = abi,
            apiLevel          = apiLevel,
            manufacturer      = manufacturer,
            socModel          = socModel,
            socFamily         = socFamily,
        )
        // Full snapshot — explains why some models are filtered out of the
        // picker (the catalog uses safeModelBudgetMb, derived from availRam).
        DebugLogger.log("PROFILE",
            "tier=${profile.tier}  totalRam=${totalRamMb}MB  avail=${availRamMb}MB  " +
            "budget=${safeModelBudgetMb}MB  storage=${availStorageMb}MB  " +
            "gpu=$gpuSafe  npu=$npuSafe  cores=$cpuCores  abi=$abi  lowRamDevice=$isLowRamDevice")
        return profile
    }

    /**
     * Reads the SoC model string using the official Android API (API 31+)
     * with a fallback to hardware/board strings for older devices.
     */
    private fun detectSocModel(): String {
        // Official API: Build.SOC_MODEL available since API 31 (Android 12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (soc.isNotBlank() && soc != Build.UNKNOWN) return soc
        }
        // Fallback: BOARD string often contains chipset name (e.g., "kalama" for SM8550)
        if (Build.BOARD.isNotBlank() && Build.BOARD != Build.UNKNOWN) return Build.BOARD
        return Build.HARDWARE.orEmpty()
    }

    /**
     * Maps a raw SoC model string to a high-level SoC family for model selection.
     * Pattern matches against known Qualcomm (Snapdragon) naming.
     */
    private fun classifySoc(socModel: String): SocFamily {
        val s = socModel.lowercase()
        return when {
            // Snapdragon 8 Gen 3 and newer flagship (SM8750+)
            s.contains("sm8750") || s.contains("8gen3") -> SocFamily.QUALCOMM_SM8750
            // Snapdragon 8 Gen 2 (SM8550)
            s.contains("sm8550") || s.contains("8gen2") || s.contains("kalama") -> SocFamily.QUALCOMM_SM8550
            // Snapdragon 8 Gen 1 / 8+ Gen 1 (SM8450/SM8475)
            s.contains("sm8450") || s.contains("sm8475") || s.contains("waipio") -> SocFamily.QUALCOMM_GENERIC
            // Any other Qualcomm
            s.contains("sm") || s.contains("qcs") || s.contains("snapdragon") -> SocFamily.QUALCOMM_GENERIC
            // MediaTek
            s.contains("mt") || s.contains("dimensity") -> SocFamily.MEDIATEK
            // Google Tensor
            s.contains("tensor") || s.contains("gs") -> SocFamily.GOOGLE_TENSOR
            // Samsung Exynos
            s.contains("exynos") -> SocFamily.SAMSUNG_EXYNOS
            else -> SocFamily.GENERIC
        }
    }

    private fun getVulkanVersion(): String? = runCatching {
        val pm = context.packageManager
        when {
            pm.hasSystemFeature("android.hardware.vulkan.version") -> {
                val v = pm.systemAvailableFeatures
                    .firstOrNull { it.name == "android.hardware.vulkan.version" }
                    ?.version ?: return@runCatching null
                "${(v shr 22) and 0x3FF}.${(v shr 12) and 0x3FF}.${v and 0xFFF}"
            }
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) -> "1.0"
            else -> null
        }
    }.getOrNull()
}
