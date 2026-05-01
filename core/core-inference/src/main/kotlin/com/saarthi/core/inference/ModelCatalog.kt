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

        // ── GEMMA 4 MODELS (Latest, April 2026) ──────────────────────────────────
        //    Frontier-level reasoning + audio/image support + 128K context
        //    Apache 2.0 License: https://ai.google.dev/gemma/docs/gemma_4_license

        ModelEntry(
            id            = "gemma4-e2b-it-litert-int8",
            displayName   = "Gemma 4 E2B IT · LiteRT INT8  ⭐ Latest",
            description   = "Google Gemma 4 E2B (April 2026) — Frontier reasoning + audio/image support. 2.3B effective parameters. GPU-accelerated: 25–40 tok/s. ~1.8 GB download. Best all-around choice for LOW/MID phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-4-E2B-it/resolve/main/gemma-4-e2b-it-q8.task",
            fileSizeBytes = 1_887_436_135L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma4",
            contextLength = 128_000,
            tags          = listOf("Recommended", "LiteRT GPU", "Google", "Gemma 4", "Frontier", "Audio", "Vision", "Latest"),
        ),

        ModelEntry(
            id            = "gemma4-e4b-it-litert-int8",
            displayName   = "Gemma 4 E4B IT · LiteRT INT8  ⭐ Latest",
            description   = "Google Gemma 4 E4B (April 2026) — Frontier reasoning with enhanced capabilities. 4.5B effective parameters. GPU-accelerated: 20–30 tok/s. ~2.8 GB download. Best quality for MID/FLAGSHIP phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-4-E4B-it/resolve/main/gemma-4-e4b-it-q8.task",
            fileSizeBytes = 2_872_905_728L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4",
            contextLength = 128_000,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 4", "Frontier", "Audio", "Vision", "Best Quality", "Latest"),
        ),

        // ── Gemma 3n E2B IT · LiteRT (mobile-first MatFormer, fallback LOW/MID) ─

        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n E2B IT · LiteRT INT4  ⚡ Fallback",
            description   = "Google Gemma 3n E2B — mobile-first MatFormer architecture. GPU-accelerated: 20–35 tok/s. ~1.4 GB download. Fallback choice for stable inference on older devices.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            fileSizeBytes = 3_136_226_711L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 1280,
            tags          = listOf("Recommended", "LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Fast"),
        ),

        // ── Gemma 3n E4B IT · LiteRT (mobile-first MatFormer, recommended FLAGSHIP) ─

        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n E4B IT · LiteRT INT4  ⚡",
            description   = "Google Gemma 3n E4B — mobile-first MatFormer. GPU-accelerated: 15–25 tok/s. ~2.6 GB download. Best quality on flagship phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            fileSizeBytes = 2_684_354_560L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 1280,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Best Quality"),
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
