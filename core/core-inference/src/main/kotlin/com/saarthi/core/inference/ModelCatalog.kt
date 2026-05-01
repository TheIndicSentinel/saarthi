package com.saarthi.core.inference

import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.LoraEntry
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.model.SocFamily
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelCatalog @Inject constructor() {

    /**
     * Returns the optimal model list for a given device profile.
     *
     * Official approach (Google AI Edge Gallery pattern):
     *   1. Prefer device-specific model files (Qualcomm QNN/Hexagon NPU) when the
     *      SoC matches — these are compiled against the hardware NPU for maximum speed.
     *   2. Fall back to the generic file for all other devices.
     *   3. Filter by memory safety budget.
     *
     * The MediaPipe LlmInference API then handles GPU/CPU backend selection
     * internally based on what the model file supports.
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        // Build a set of base model IDs that have a device-specific variant
        // available for this SoC. Those base IDs are excluded in favour of
        // the hardware-optimised entry.
        val socOptimisedBaseIds = allModels
            .filter { it.socTarget == profile.socFamily && it.socTarget != SocFamily.GENERIC }
            .map { it.baseModelId }
            .toSet()

        return allModels.filter { model ->
            val isVisible = when {
                // Show device-specific entry only if SoC matches
                model.socTarget != SocFamily.GENERIC -> model.socTarget == profile.socFamily
                // Hide the generic entry when a better SoC-specific one is available
                model.baseModelId in socOptimisedBaseIds -> false
                else -> true
            }
            isVisible && model.isSafeFor(profile)
        }
    }

    /**
     * Models that exceed the safe budget but might still run if the user
     * closes all background apps.
     */
    fun riskyFor(profile: DeviceProfile): List<ModelEntry> {
        return allModels.filter { !it.isSafeFor(profile) && (it.fileSizeBytes / 1_048_576) < profile.availableRamMb }
    }

    // ── Model catalog ─────────────────────────────────────────────────────────
    //
    // Official approach (matches Google AI Edge Gallery architecture):
    //   • Each base model may have multiple entries: one generic + device-specific.
    //   • Device-specific entries (socTarget != GENERIC) use QNN/Hexagon NPU delegates
    //     compiled into the .litertlm bundle — fastest on matched hardware.
    //   • Generic entries use CPU + OpenCL GPU path with automatic CPU fallback.
    //   • recommendedFor(profile) selects the best entry per SoC automatically.
    //   • MediaPipe LlmInference handles all backend negotiation internally.
    //
    // Tier mapping:
    //   FLAGSHIP : ≥ 8 GB total RAM + Vulkan  (e.g. Samsung S23/S24, Pixel 8 Pro)
    //   MID      : 4–8 GB total RAM           (e.g. Samsung A54, Pixel 7a)
    //   LOW      : < 4 GB total RAM           (e.g. budget devices)
    //
    // LiteRT models require HuggingFace token + accepted Google licence on the model page.
    // The app's embedded HF token handles auth automatically.
    //

    val allModels: List<ModelEntry> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 4 — LITERT-LM (MediaPipe 0.10.14+ | Google official)
        // ══════════════════════════════════════════════════════════════════════
        //  Each model has a GENERIC entry (all devices) and optionally a
        //  device-specific entry (Qualcomm SM8750) with QNN/Hexagon NPU.
        //  recommendedFor() picks the best match automatically.

        // ── Gemma 4 E2B ─────────────────────────────────────────────────────

        // Gemma 4 E2B · Qualcomm SM8750 (Snapdragon 8 Elite) — QNN/Hexagon NPU
        ModelEntry(
            id            = "gemma4-e2b-it-qualcomm-sm8750",
            displayName   = "Gemma 4 E2B IT · Qualcomm NPU  🚀 New",
            description   = "Gemma 4 E2B optimised for Snapdragon 8 Elite (SM8750). Uses QNN/Hexagon NPU for maximum on-device speed. ~3.0 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            fileSizeBytes = 3_242_086_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 128_000,
            tags          = listOf("New", "QNN NPU", "Qualcomm", "Gemma 4", "Fastest"),
            socTarget     = SocFamily.QUALCOMM_SM8750,
            baseModelId   = "gemma4-e2b-it-litert-int8",
        ),

        // Gemma 4 E2B · Generic (all devices) — CPU + OpenCL with auto fallback
        ModelEntry(
            id            = "gemma4-e2b-it-litert-int8",
            displayName   = "Gemma 4 E2B IT · LiteRT  🚀 New",
            description   = "Google's latest Gemma 4. Frontier reasoning + audio/vision. Auto-selects best backend (GPU/CPU) per device. ~2.6 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 2_772_434_944L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 128_000,
            tags          = listOf("New", "LiteRT", "Google", "Gemma 4", "Reasoning", "Audio", "Vision"),
        ),

        // ── Gemma 4 E4B ─────────────────────────────────────────────────────

        // Gemma 4 E4B · Generic (all devices) — CPU + OpenCL with auto fallback
        // NOTE: Requires FLAGSHIP tier — 3.5GB model needs 4GB+ RAM headroom on CPU.
        ModelEntry(
            id            = "gemma4-e4b-it-litert-int8",
            displayName   = "Gemma 4 E4B IT · LiteRT  🚀 New",
            description   = "High-performance Gemma 4. Superior reasoning and multimodal capabilities. Auto-selects best backend. ~3.5 GB download. Requires flagship device.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileSizeBytes = 3_758_096_384L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma4-e4b",
            contextLength = 128_000,
            tags          = listOf("New", "LiteRT", "Google", "Gemma 4", "Best Quality", "Audio", "Vision", "Flagship Only"),
        ),



        // ── Gemma 3n E2B IT · LiteRT (mobile-first MatFormer, recommended LOW/MID) ─

        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n E2B IT · LiteRT INT4  ⭐ Recommended",
            description   = "Google Gemma 3n E2B — mobile-first MatFormer architecture. GPU-accelerated: 20–35 tok/s. ~1.4 GB download. Best choice for LOW/MID phones. Proven stable & fast.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            fileSizeBytes = 1_474_354_560L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 1280,
            tags          = listOf("Recommended", "LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Fast", "Stable"),
        ),


        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n E4B IT · LiteRT INT4  ⭐ Best Quality",
            description   = "Google Gemma 3n E4B — mobile-first MatFormer. GPU-accelerated: 15–25 tok/s. ~2.6 GB download. Best quality on MID/FLAGSHIP phones. Proven & reliable.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            fileSizeBytes = 2_684_354_560L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 1280,
            tags          = listOf("Recommended", "LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Best Quality", "Stable"),
        ),

        // ── Gemma 3 1B IT · LiteRT (ultra-compact, any phone) ─────────────────

        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 1B IT · LiteRT INT4  ⚡",
            description   = "Google Gemma 3 1B — smallest Gemma, GPU-accelerated. ~750 MB download. Works on any phone with ≥2 GB RAM.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            fileSizeBytes = 554_661_243L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 1280,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 3", "Ultra-Compact", "Budget"),
        ),

        // ── Gemma 2 2B IT · LiteRT (previous-gen, reliable mid-range) ──────────

        ModelEntry(
            id            = "gemma2-2b-it-litert-int8",
            displayName   = "Gemma 2 2B IT · LiteRT INT8  ⚡",
            description   = "Google Gemma 2 2B — proven previous-gen model, GPU-accelerated. ~1.3 GB download. Reliable for mid-range phones.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
            fileSizeBytes = 2_713_274_466L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 2", "Stable"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────

    val loraEntries: List<LoraEntry> = emptyList()

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? = null

    fun loraById(id: String): LoraEntry? = null
}
