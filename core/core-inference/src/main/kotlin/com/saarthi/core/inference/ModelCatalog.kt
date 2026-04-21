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

    // ── Base models ───────────────────────���──────────────────────────��────────
    // Ordered best-first within each tier.

    val allModels: List<ModelEntry> = listOf(

        // FLAGSHIP (≥8GB RAM + Vulkan) ─────────────────────────────────────────
        ModelEntry(
            id          = "qwen2.5-7b-instruct-q4",
            displayName = "Qwen 2.5 7B · Q4_K_M",
            description = "Best quality. Strong reasoning and multilingual. Ideal for S23 Ultra and Pixel 8 Pro.",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 4_683_341_824L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "qwen2.5",
            nGpuLayers  = 0,
            contextLength = 2048,
            tags        = listOf("Recommended", "Best Quality"),
        ),

        ModelEntry(
            id          = "llama3.2-3b-instruct-q4",
            displayName = "Llama 3.2 3B · Q4_K_M",
            description = "Meta's compact Llama 3.2 — fast, good reasoning. Fits flagship and high mid-range.",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 2_019_686_400L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "llama3.2",
            nGpuLayers  = 0,
            contextLength = 2048,
            tags        = listOf("Fast", "Meta"),
        ),

        // MID (4–8GB RAM) ──────────────────────────────────────────────────────
        ModelEntry(
            id          = "phi3.5-mini-instruct-q4",
            displayName = "Phi 3.5 Mini · Q4_K_M",
            description = "Microsoft Phi 3.5 — strong reasoning per parameter, 3.8B fits mid-range devices.",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            fileSizeBytes = 2_393_407_488L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "phi3.5",
            nGpuLayers  = 0,
            contextLength = 2048,
            tags        = listOf("Microsoft", "Strong Reasoning"),
        ),

        ModelEntry(
            id          = "gemma2-2b-it-q4",
            displayName = "Gemma 2 2B IT · Q4_K_M",
            description = "Google Gemma 2 2B via llama.cpp — real streaming, better than the MediaPipe variant.",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileSizeBytes = 1_644_167_168L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma2",
            nGpuLayers  = 0,
            contextLength = 2048,
            tags        = listOf("Compact", "Google"),
        ),

        // MID fallback / LOW ───────────────────────────────────────────────────
        ModelEntry(
            id          = "llama3.2-1b-instruct-q4",
            displayName = "Llama 3.2 1B · Q4_K_M",
            description = "Smallest Llama 3.2 — only ~800 MB, runs on entry-level devices with 3 GB RAM.",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 812_851_200L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.LOW,
            modelFamily  = "llama3.2",
            nGpuLayers  = 0,
            contextLength = 2048,
            tags        = listOf("Smallest", "Low RAM"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────
    //
    // Architecture:
    //   • One adapter file per (pack × model-family) pair.
    //   • Trained via QLoRA on a curated Indian-context dataset for each domain.
    //   • File size: ~50–300 MB (vs 1.6–4.7 GB base model) — economical to download.
    //   • Runtime: loaded via llama_adapter_lora_init(); swapped in <1s, no model reload.
    //   • MediaPipe models (gemma2-mediapipe-cpu) do NOT support LoRA — those users
    //     get domain behaviour via system-prompt only (still useful).
    //
    // Download URLs below are PLACEHOLDER — replace with actual Hugging Face paths
    // once adapters are trained and published.

    val loraEntries: List<LoraEntry> = listOf(

        // ── Qwen 2.5 adapters (flagship) ──────────────────────────────────────
        LoraEntry(
            id          = "knowledge-qwen2.5",
            packType    = PackType.KNOWLEDGE,
            modelFamily = "qwen2.5",
            displayName = "Knowledge Pack · Qwen 2.5",
            description = "NCERT + competitive exam knowledge layer for Indian students.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/knowledge_qwen2.5_q4.gguf",
            fileSizeBytes = 157_286_400L,
        ),
        LoraEntry(
            id          = "money-qwen2.5",
            packType    = PackType.MONEY,
            modelFamily = "qwen2.5",
            displayName = "Money Mentor · Qwen 2.5",
            description = "Indian finance, banking, and government scheme knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/money_qwen2.5_q4.gguf",
            fileSizeBytes = 104_857_600L,
        ),
        LoraEntry(
            id          = "kisan-qwen2.5",
            packType    = PackType.KISAN,
            modelFamily = "qwen2.5",
            displayName = "Kisan Saarthi · Qwen 2.5",
            description = "Indian agriculture, crop and mandi knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/kisan_qwen2.5_q4.gguf",
            fileSizeBytes = 104_857_600L,
        ),
        LoraEntry(
            id          = "fieldexpert-qwen2.5",
            packType    = PackType.FIELD_EXPERT,
            modelFamily = "qwen2.5",
            displayName = "Field Expert · Qwen 2.5",
            description = "Vocational trades knowledge layer (electrician, plumber, mason).",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/fieldexpert_qwen2.5_q4.gguf",
            fileSizeBytes = 104_857_600L,
        ),

        // ── Llama 3.2 adapters (flagship) ─────────────────────────────────────
        LoraEntry(
            id          = "knowledge-llama3.2",
            packType    = PackType.KNOWLEDGE,
            modelFamily = "llama3.2",
            displayName = "Knowledge Pack · Llama 3.2",
            description = "NCERT + competitive exam knowledge layer for Indian students.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/knowledge_llama32_q4.gguf",
            fileSizeBytes = 131_072_000L,
        ),
        LoraEntry(
            id          = "money-llama3.2",
            packType    = PackType.MONEY,
            modelFamily = "llama3.2",
            displayName = "Money Mentor · Llama 3.2",
            description = "Indian finance knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/money_llama32_q4.gguf",
            fileSizeBytes = 104_857_600L,
        ),
        LoraEntry(
            id          = "kisan-llama3.2",
            packType    = PackType.KISAN,
            modelFamily = "llama3.2",
            displayName = "Kisan Saarthi · Llama 3.2",
            description = "Indian agriculture knowledge layer.",
            downloadUrl = "https://huggingface.co/saarthi-ai/adapters/resolve/main/kisan_llama32_q4.gguf",
            fileSizeBytes = 104_857_600L,
        ),

        // ── Gemma 2 adapters (mid-range) ────────────────────────────────���────
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

    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> =
        allModels.filter { it.requiredTier.ordinal <= profile.tier.ordinal }

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    /**
     * Find the best LoRA adapter for [packType] given the currently loaded [modelFamily].
     * Returns null if no adapter exists yet (pack uses system-prompt-only mode).
     */
    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? =
        loraEntries.find { it.packType == packType && it.modelFamily == modelFamily }

    fun loraById(id: String): LoraEntry? = loraEntries.find { it.id == id }
}
