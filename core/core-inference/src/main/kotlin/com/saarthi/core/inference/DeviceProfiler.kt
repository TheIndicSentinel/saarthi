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
 * 4. **GPU safety is SoC-aware**: Checks Vulkan support, OEM driver stability
 *    history, and whether the available RAM can handle shared-memory overhead.
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

        val totalRamMb = memInfo.totalMem / 1_048_576
        val availRamMb = memInfo.availMem / 1_048_576

        // ── Safe Model Budget ────────────────────────────────────────────────
        // Rule: Use at most 75% of currently free RAM, but never more than
        // 60% of total RAM. This ensures the OS always has breathing room.
        val budgetFromAvail = (availRamMb * 75) / 100
        val budgetFromTotal = (totalRamMb * 60) / 100
        val safeModelBudgetMb = minOf(budgetFromAvail, budgetFromTotal)

        // ── Storage ──────────────────────────────────────────────────────────
        val storageDir = context.getExternalFilesDir(null) ?: context.filesDir
        val availStorageMb = runCatching {
            val stat = StatFs(storageDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / 1_048_576
        }.getOrDefault(0L)

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

        // ── SoC Family Detection (must precede GPU/NPU safety checks) ─────────
        val socModel = detectSocModel()
        val socFamily = classifySoc(socModel)

        // ── GPU Safety: SoC-aware backend policy (litertlm-android era) ────────
        //
        // litertlm-android:0.10.0 is the same runtime used by Google AI Edge Gallery,
        // which runs correctly on all devices below — including SM8550/Android 16.
        //
        // Per-chip policy:
        //
        //  QUALCOMM SM8750  — GPU always (best-in-class Adreno 830, no known issues).
        //  QUALCOMM SM8550  — CPU only. Confirmed via conv-start/conv-ready diagnostic on
        //                     SM-S918B (Android 16/API 36): createConversation() crashes inside
        //                     the GPU warm-up at ANY maxNumTokens (512 included). Crash times
        //                     decrease with each retry (26s→7.6s→4.3s), consistent with Samsung's
        //                     GPU power quota being depleted. CPU via XNNPACK is stable.
        //  QUALCOMM GENERIC — GPU enabled. Per-model crash recovery bans if runtime fault.
        //  GOOGLE TENSOR    — GPU always. Stable OpenCL on all API levels.
        //  SAMSUNG EXYNOS   — CPU on API 34+. OpenCL driver regression is OEM-level.
        //  MEDIATEK flagship — GPU on ≥8GB RAM (Mali OpenCL stable on Dimensity flagship).
        //  MEDIATEK other   — CPU. OpenCL driver quality too variable for production.
        //  GENERIC / unknown — GPU if 4GB+ avail RAM.
        val gpuSafe: Boolean = when {
            availRamMb < 3_000 -> false  // GPU VRAM overhead risks LMK on low-RAM devices
            !hasVulkan -> false           // No Vulkan = no GPU delegate in LiteRT
            else -> when (socFamily) {
                SocFamily.QUALCOMM_SM8750  -> true
                SocFamily.QUALCOMM_SM8550  -> false
                SocFamily.QUALCOMM_GENERIC -> true
                SocFamily.GOOGLE_TENSOR    -> true
                SocFamily.SAMSUNG_EXYNOS   -> apiLevel < 34
                SocFamily.MEDIATEK         -> totalRamMb >= 8_000 && availRamMb >= 4_000
                SocFamily.GENERIC          -> availRamMb >= 4_000
            }
        }

        val gpuSafeReason = when {
            availRamMb < 3_000 -> "low RAM (avail=${availRamMb}MB < 3000MB)"
            !hasVulkan         -> "no Vulkan support"
            socFamily == SocFamily.QUALCOMM_SM8550 ->
                "SM8550: GPU crashes in litertlm createConversation() at any maxNumTokens"
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
        // Device-specific .litertlm bundles with QNN support currently exist only for
        // Snapdragon 8 Gen 3 (SM8750). ModelCatalog includes a QNN-specific model
        // entry for this SoC. All other SoCs fall back to GPU or CPU.
        //
        // NPU additionally requires sufficient RAM (same floor as GPU) to keep the
        // DSP runtime and model data in memory simultaneously.
        val npuSafe: Boolean = when {
            availRamMb < 3_000            -> false
            socFamily == SocFamily.QUALCOMM_SM8750 -> true
            else                          -> false
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        DebugLogger.log("PROFILER",
            "GPU policy: gpuSafe=$gpuSafe  reason=$gpuSafeReason  " +
            "npuSafe=$npuSafe  soc=$socModel  api=$apiLevel  manufacturer=$manufacturer")

        return DeviceProfile(
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
            abi               = abi,
            apiLevel          = apiLevel,
            manufacturer      = manufacturer,
            socModel          = socModel,
            socFamily         = socFamily,
        )
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
