package com.saarthi.core.inference

import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.LoraEntry
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.core.inference.model.PackType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelCatalog @Inject constructor() {

    /**
     * Snapshots the device's runtime state and returns only the models that are
     * safe to run within the current memory budget.
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        return allModels.filter { it.isSafeFor(profile) }
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
    // Primary backend: Google AI Edge LiteRT (.task bundles via MediaPipe Tasks GenAI).
    //   • GPU-accelerated via OpenCL / Vulkan delegates — 20–50 tok/s on modern Adreno/Mali.
    //   • Google's official distribution format for Gemma mobile models.
    //   • Gemma 3n MatFormer kernels are co-designed for LiteRT INT8 — NOT for GGUF.
    //   • Automatic CPU fallback (XNNPACK NEON) when GPU delegate unavailable.
    //
    // Fallback backend: llama.cpp (.gguf files).
    //   • CPU-only on this device (Vulkan crashes confirmed on Samsung Android 16).
    //   • 3–8 tok/s — functional but slow. Shown as "offline / fallback" options.
    //   • Community models or devices where LiteRT delegates are unavailable.
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
        //  LITERT — PRIMARY (GPU-accelerated, Google official)
        // ══════════════════════════════════════════════════════════════════════

        // ── GEMMA 4 MODELS ───────────────────────────────────────────────────
        //    Frontier-level reasoning + audio/image support + 128K context
        //    Official Google LiteRT bundles (.task)
        //    Apache 2.0 License: https://ai.google.dev/gemma/docs/gemma_4_license

        // Gemma 4 E2B IT · LiteRT (Recommended for most devices)
        ModelEntry(
            id            = "gemma4-e2b-it-litert-int8",
            displayName   = "Gemma 4 E2B IT · LiteRT INT8  🚀 New",
            description   = "Google's latest Gemma 4 model. Frontier-level reasoning with audio/vision support. GPU-accelerated: 25-40 tok/s. ~1.8 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-q8.task",
            fileSizeBytes = 1_943_493_017L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma4-e2b",
            contextLength = 128_000,
            tags          = listOf("New", "LiteRT GPU", "Google", "Gemma 4", "Reasoning", "Audio", "Vision"),
        ),

        // Gemma 4 E4B IT · LiteRT (For high-end devices)
        ModelEntry(
            id            = "gemma4-e4b-it-litert-int8",
            displayName   = "Gemma 4 E4B IT · LiteRT INT8  🚀 New",
            description   = "High-performance Gemma 4 model. Superior reasoning and multimodal capabilities. GPU-accelerated: 20-30 tok/s. ~2.8 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-q8.task",
            fileSizeBytes = 3_693_671_219L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e4b",
            contextLength = 128_000,
            tags          = listOf("New", "LiteRT GPU", "Google", "Gemma 4", "Best Quality", "Audio", "Vision"),
        ),

        // ── Gemma 3n E2B IT · LiteRT (mobile-first MatFormer, recommended LOW/MID) ─

        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n E2B IT · LiteRT INT4  ⭐ Recommended",
            description   = "Google Gemma 3n E2B — mobile-first MatFormer architecture. GPU-accelerated: 20–35 tok/s. ~1.4 GB download. Best choice for LOW/MID phones. Proven stable & fast.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            fileSizeBytes = 3_136_226_711L,
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
