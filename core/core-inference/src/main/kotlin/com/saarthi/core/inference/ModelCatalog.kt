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
     * The litertlm-android Engine API then handles GPU/CPU/NPU backend selection
     * based on DeviceProfiler policy and per-model crash history.
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        val socOptimisedBaseIds = allModels
            .filter { it.socTarget == profile.socFamily && it.socTarget != SocFamily.GENERIC }
            .map { it.baseModelId }
            .toSet()

        return allModels.filter { model ->
            val isVisible = when {
                model.socTarget != SocFamily.GENERIC -> model.socTarget == profile.socFamily
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
    // All models use the .litertlm format required by litertlm-android:0.10.0.
    // This is the same runtime used by Google AI Edge Gallery.
    //
    // Architecture:
    //   • Each base model may have: one generic entry + one device-specific entry.
    //   • Device-specific entries (socTarget != GENERIC) use QNN/Hexagon NPU.
    //   • Generic entries use CPU + OpenCL GPU with automatic CPU fallback.
    //   • recommendedFor(profile) selects the best entry per SoC automatically.
    //
    // Tier mapping:
    //   FLAGSHIP : ≥ 10 GB total RAM  (Samsung S23 Ultra, Pixel 8 Pro, etc.)
    //   MID      : 6–10 GB total RAM  (Samsung A54, Pixel 7a, etc.)
    //   LOW      : 3.5–6 GB total RAM (budget phones)
    //   MINIMAL  : < 3.5 GB total RAM (ultra-budget — no model safe to load)
    //
    // HuggingFace sources (all require accepted Google licence + embedded HF token):
    //   Gemma 4   : litert-community/gemma-4-{E2B,E4B}-it-litert-lm
    //   Gemma 3n  : google/gemma-3n-{E2B,E4B}-it-litert-lm
    //   Gemma 3 1B: litert-community/Gemma3-1B-IT
    //

    val allModels: List<ModelEntry> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 4 — LITERT-LM
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 4 E2B · Qualcomm SM8750 (QNN/Hexagon NPU) ──────────────────
        ModelEntry(
            id            = "gemma4-e2b-it-qualcomm-sm8750",
            displayName   = "Gemma 4 E2B IT · Qualcomm NPU  🚀 Fastest",
            description   = "Gemma 4 E2B compiled for Snapdragon 8 Gen 3 QNN/Hexagon NPU. Maximum speed on matched hardware. ~3.0 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            fileSizeBytes = 3_242_086_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 8192,
            tags          = listOf("New", "NPU", "Qualcomm", "Gemma 4", "Fastest"),
            socTarget     = SocFamily.QUALCOMM_SM8750,
            baseModelId   = "gemma4-e2b-it-litert",
        ),

        // ── Gemma 4 E2B · Generic (all devices) ──────────────────────────────
        ModelEntry(
            id            = "gemma4-e2b-it-litert",
            displayName   = "Gemma 4 E2B IT · LiteRT  🚀 New",
            description   = "Google Gemma 4 E2B. Frontier reasoning + multimodal. Auto-selects best backend (GPU/CPU). ~2.5 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 2_583_085_056L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 8192,
            tags          = listOf("New", "LiteRT", "Google", "Gemma 4", "Reasoning"),
        ),

        // ── Gemma 4 E4B · Generic (all devices) ──────────────────────────────
        ModelEntry(
            id            = "gemma4-e4b-it-litert",
            displayName   = "Gemma 4 E4B IT · LiteRT  🚀 Best Quality",
            description   = "Gemma 4 E4B. Superior reasoning and multimodal capabilities. Auto-selects best backend. ~3.5 GB download. Requires flagship device.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileSizeBytes = 3_758_096_384L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma4-e4b",
            contextLength = 8192,
            tags          = listOf("New", "LiteRT", "Google", "Gemma 4", "Best Quality", "Flagship Only"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3n — LITERT-LM (mobile-first MatFormer architecture)
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3n E2B IT ───────────────────────────────────────────────────
        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n E2B IT · LiteRT INT4  ⭐ Recommended",
            description   = "Google Gemma 3n E2B — mobile-first MatFormer architecture. GPU-accelerated: 20–35 tok/s. ~3.0 GB download. Best choice for mid-range phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileSizeBytes = 3_655_827_456L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 8192,
            tags          = listOf("Recommended", "LiteRT", "Google", "Gemma 3n", "Mobile-First", "Fast"),
        ),

        // ── Gemma 3n E4B IT ───────────────────────────────────────────────────
        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n E4B IT · LiteRT INT4  ⭐ Best Quality",
            description   = "Google Gemma 3n E4B — mobile-first MatFormer. GPU-accelerated: 15–25 tok/s. ~4.0 GB download. Best quality on MID/FLAGSHIP phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 4_405_655_031L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 8192,
            tags          = listOf("Recommended", "LiteRT", "Google", "Gemma 3n", "Mobile-First", "Best Quality"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3 1B — LITERT-LM (ultra-compact, any phone)
        // ══════════════════════════════════════════════════════════════════════

        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 1B IT · LiteRT INT4  ⚡ Ultra-Fast",
            description   = "Google Gemma 3 1B — smallest Gemma, GPU-accelerated. ~584 MB download. Works on any phone with ≥2 GB available RAM.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileSizeBytes = 584_417_280L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("LiteRT", "Google", "Gemma 3", "Ultra-Compact", "Budget"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────

    val loraEntries: List<LoraEntry> = emptyList()

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? = null

    fun loraById(id: String): LoraEntry? = null
}
