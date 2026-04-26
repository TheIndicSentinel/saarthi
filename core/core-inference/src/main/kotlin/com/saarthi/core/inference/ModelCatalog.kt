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

    val allModels: List<ModelEntry> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        //  LITERT — PRIMARY (GPU-accelerated, Google official)
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3n E2B IT · LiteRT (mobile-first MatFormer, recommended LOW/MID) ─

        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int8",
            displayName   = "Gemma 3n E2B IT · LiteRT INT8  ⚡ Recommended",
            description   = "Google Gemma 3n E2B — mobile-first MatFormer architecture. GPU-accelerated via LiteRT: 20–35 tok/s on Adreno 7xx/8xx. ~1.4 GB download. Best choice for mid-range and flagship phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int8.task",
            fileSizeBytes = 1_468_006_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("Recommended", "LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Fast"),
        ),

        // ── Gemma 3n E4B IT · LiteRT (mobile-first MatFormer, recommended FLAGSHIP) ─

        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int8",
            displayName   = "Gemma 3n E4B IT · LiteRT INT8  ⚡",
            description   = "Google Gemma 3n E4B — mobile-first MatFormer, same quality as 4B at lower cost. GPU-accelerated: 15–25 tok/s. ~2.6 GB download. Best quality on flagship phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int8.task",
            fileSizeBytes = 2_684_354_560L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 3n", "Mobile-First", "Best Quality"),
        ),

        // ── Gemma 3 1B IT · LiteRT (ultra-compact, any phone) ─────────────────

        ModelEntry(
            id            = "gemma3-1b-it-litert-int8",
            displayName   = "Gemma 3 1B IT · LiteRT INT8  ⚡",
            description   = "Google Gemma 3 1B — smallest Gemma, GPU-accelerated. ~750 MB download. Works on any Android phone with ≥2 GB RAM. Great for budget devices.",
            downloadUrl   = "https://huggingface.co/google/gemma-3-1b-it-litert-preview/resolve/main/gemma3-1b-it-int8.task",
            fileSizeBytes = 786_432_000L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 3", "Ultra-Compact", "Budget"),
        ),

        // ── Gemma 2 2B IT · LiteRT (previous-gen, reliable mid-range) ──────────

        ModelEntry(
            id            = "gemma2-2b-it-litert-int8",
            displayName   = "Gemma 2 2B IT · LiteRT INT8  ⚡",
            description   = "Google Gemma 2 2B — proven previous-gen model, GPU-accelerated. ~1.3 GB download. Reliable for mid-range phones with 4–6 GB RAM.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-cpu-int8.litertlm",
            fileSizeBytes = 1_363_148_800L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("LiteRT GPU", "Google", "Gemma 2", "Stable"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GGUF — FALLBACK (CPU-only, community / custom models)
        // ══════════════════════════════════════════════════════════════════════

        ModelEntry(
            id            = "gemma3n-e2b-it-q4",
            displayName   = "Gemma 3n E2B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Gemma 3n E2B in GGUF format for llama.cpp. CPU-only on most Android devices: ~5 tok/s. Use this only if LiteRT models above are unavailable. ~2.6 GB.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3n-E2B-it-GGUF/resolve/main/google_gemma-3n-E2B-it-Q4_K_M.gguf",
            fileSizeBytes = 2_787_213_312L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 3n"),
        ),

        ModelEntry(
            id            = "gemma3n-e4b-it-q4",
            displayName   = "Gemma 3n E4B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Gemma 3n E4B in GGUF format for llama.cpp. CPU-only. ~4 GB. Use only if LiteRT is unavailable. Requires flagship phone with ≥8 GB RAM.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3n-E4B-it-GGUF/resolve/main/google_gemma-3n-E4B-it-Q4_K_M.gguf",
            fileSizeBytes = 4_236_509_184L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 3n"),
        ),

        ModelEntry(
            id            = "gemma3-4b-it-q4",
            displayName   = "Gemma 3 4B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Google Gemma 3 4B in GGUF format. ~2.4 GB. CPU-only on Android. Solid multilingual quality for mid-range phones if LiteRT is unavailable.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
            fileSizeBytes = 2_489_348_096L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 3"),
        ),

        ModelEntry(
            id            = "gemma3-12b-it-q4",
            displayName   = "Gemma 3 12B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Google Gemma 3 12B — maximum quality in GGUF format. ~7.7 GB, flagship only. CPU-only; very slow (~2 tok/s). Only for power users who need peak accuracy.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf",
            fileSizeBytes = 7_730_941_952L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 3", "Max Quality"),
        ),

        ModelEntry(
            id            = "gemma3-1b-it-q4",
            displayName   = "Gemma 3 1B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Gemma 3 1B in GGUF format. ~650 MB. CPU-only fallback for entry-level phones. Use the LiteRT version above for better speed.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
            fileSizeBytes = 681_574_400L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 3"),
        ),

        ModelEntry(
            id            = "gemma2-2b-it-q4",
            displayName   = "Gemma 2 2B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Gemma 2 2B in GGUF format. ~1.7 GB. CPU-only fallback. Use the LiteRT version for 4–6× faster inference.",
            downloadUrl   = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileSizeBytes = 1_708_582_752L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("GGUF", "CPU", "Fallback", "Google", "Gemma 2"),
        ),

        ModelEntry(
            id            = "gemma2-9b-it-q4",
            displayName   = "Gemma 2 9B IT · GGUF Q4_K_M  (CPU fallback)",
            description   = "Google Gemma 2 9B in GGUF format. ~5.9 GB, flagship only. Strong multilingual quality but very slow on CPU. No LiteRT version available.",
            downloadUrl   = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            fileSizeBytes = 5_905_580_032L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("GGUF", "CPU", "Google", "Gemma 2"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────
    // LoRA is supported only by llama.cpp. LiteRT adapters are no-ops at runtime.

    val loraEntries: List<LoraEntry> = listOf(

        // ── Gemma 3 adapters ──────────────────────────────────────────────────
        LoraEntry(
            id          = "knowledge-gemma3",
            packType    = PackType.KNOWLEDGE,
            modelFamily = "gemma3",
            displayName = "Knowledge Pack · Gemma 3",
            description = "NCERT + competitive exam knowledge layer for Indian students.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/knowledge_gemma3_q4.gguf",
            fileSizeBytes = 78_643_200L,
        ),
        LoraEntry(
            id          = "money-gemma3",
            packType    = PackType.MONEY,
            modelFamily = "gemma3",
            displayName = "Money Mentor · Gemma 3",
            description = "Indian finance, banking and government scheme knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/money_gemma3_q4.gguf",
            fileSizeBytes = 52_428_800L,
        ),
        LoraEntry(
            id          = "kisan-gemma3",
            packType    = PackType.KISAN,
            modelFamily = "gemma3",
            displayName = "Kisan Saarthi · Gemma 3",
            description = "Indian agriculture, crop and mandi knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/kisan_gemma3_q4.gguf",
            fileSizeBytes = 52_428_800L,
        ),

        // ── Gemma 3n adapters ─────────────────────────────────────────────────
        LoraEntry(
            id          = "knowledge-gemma3n",
            packType    = PackType.KNOWLEDGE,
            modelFamily = "gemma3n",
            displayName = "Knowledge Pack · Gemma 3n",
            description = "NCERT + competitive exam knowledge layer for Indian students.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/knowledge_gemma3n_q4.gguf",
            fileSizeBytes = 78_643_200L,
        ),
        LoraEntry(
            id          = "money-gemma3n",
            packType    = PackType.MONEY,
            modelFamily = "gemma3n",
            displayName = "Money Mentor · Gemma 3n",
            description = "Indian finance knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/money_gemma3n_q4.gguf",
            fileSizeBytes = 52_428_800L,
        ),

        // ── Gemma 2 adapters ──────────────────────────────────────────────────
        LoraEntry(
            id          = "knowledge-gemma2",
            packType    = PackType.KNOWLEDGE,
            modelFamily = "gemma2",
            displayName = "Knowledge Pack · Gemma 2",
            description = "NCERT + competitive exam knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/knowledge_gemma2_q4.gguf",
            fileSizeBytes = 78_643_200L,
        ),
        LoraEntry(
            id          = "money-gemma2",
            packType    = PackType.MONEY,
            modelFamily = "gemma2",
            displayName = "Money Mentor · Gemma 2",
            description = "Indian finance knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/money_gemma2_q4.gguf",
            fileSizeBytes = 52_428_800L,
        ),
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    /**
     * Returns models ordered best-first for this device.
     * LiteRT models are always shown before GGUF fallbacks.
     * Filters by tier, available storage (90% limit), and minimum RAM headroom (1.5 GB).
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        val storageLimitMb   = (profile.availableStorageMb * 0.90).toLong()
        val minRemainingRamMb = 1_500L
        return allModels.filter { model ->
            val modelSizeMb = model.fileSizeBytes / 1_048_576
            model.requiredTier.ordinal <= profile.tier.ordinal &&
            modelSizeMb <= storageLimitMb &&
            (profile.availableRamMb - modelSizeMb) >= minRemainingRamMb
        }.sortedWith(
            compareBy(
                { if (it.engineType == EngineType.LLAMA_CPP) 1 else 0 },
                { it.fileSizeBytes }
            )
        )
    }


    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? =
        loraEntries.find { it.packType == packType && it.modelFamily == modelFamily }

    fun loraById(id: String): LoraEntry? = loraEntries.find { it.id == id }
}
