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

        // ══ GEMMA LiteRT — official Google on-device format (.task / .litertlm) ═
        // These use the MediaPipe LlmInference engine (same as Edge Gallery).
        // Gated models require a free Google / Kaggle licence acceptance first.

        // FLAGSHIP ─────────────────────────────────────────────────────────────
        ModelEntry(
            id           = "gemma3n-e4b-litert-int4",
            displayName  = "Gemma 3n E4B IT · INT4 (LiteRT)",
            description  = "Google Gemma 3n E4B — official Edge Gallery model. Requires licence at google/gemma-3n-E4B-it-litert-lm.",
            downloadUrl  = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 5_283_061_760L,
            engineType   = EngineType.MEDIAPIPE,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "gemma3n",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3n", "LiteRT", "Requires Licence"),
        ),

        // MID ──────────────────────────────────────────────────────────────────
        ModelEntry(
            id           = "gemma4-e4b-litert-web",
            displayName  = "Gemma 4 E4B IT · LiteRT",
            description  = "Google Gemma 4 E4B — latest efficient 4B, Edge Gallery format. Great mid-range choice.",
            downloadUrl  = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task",
            fileSizeBytes = 3_178_275_840L,
            engineType   = EngineType.MEDIAPIPE,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma4",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 4", "LiteRT", "Recommended"),
        ),

        ModelEntry(
            id           = "gemma4-e2b-litert-web",
            displayName  = "Gemma 4 E2B IT · LiteRT",
            description  = "Google Gemma 4 E2B — compact efficient 2B, Edge Gallery format. Fast on mid-range phones.",
            downloadUrl  = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task",
            fileSizeBytes = 2_147_483_648L,
            engineType   = EngineType.MEDIAPIPE,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma4",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 4", "LiteRT", "Compact"),
        ),

        // LOW ──────────────────────────────────────────────────────────────────
        ModelEntry(
            id           = "gemma3-1b-litert-int4",
            displayName  = "Gemma 3 1B IT · INT4 (LiteRT)",
            description  = "Google Gemma 3 1B — only 555 MB, runs on budget phones. Requires licence at litert-community/Gemma3-1B-IT.",
            downloadUrl  = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            fileSizeBytes = 581_959_680L,
            engineType   = EngineType.MEDIAPIPE,
            requiredTier = DeviceTier.LOW,
            modelFamily  = "gemma3",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3", "LiteRT", "Requires Licence", "Smallest"),
        ),

        // ══ GEMMA FAMILY (Google — mobile-first, optimised for on-device) ═════

        // FLAGSHIP (≥8GB RAM + Vulkan) ─────────────────────────────────────────
        ModelEntry(
            id           = "gemma3-12b-it-q4",
            displayName  = "Gemma 3 12B IT · Q4_K_M",
            description  = "Google's best mobile model. Excellent multilingual + reasoning. High-end phones only.",
            downloadUrl  = "https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf",
            fileSizeBytes = 7_730_941_952L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "gemma3",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3", "Best Quality"),
        ),

        ModelEntry(
            id           = "gemma2-9b-it-q4",
            displayName  = "Gemma 2 9B IT · Q4_K_M",
            description  = "Google Gemma 2 9B — strong quality on flagship phones with ≥8 GB RAM.",
            downloadUrl  = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            fileSizeBytes = 5_905_580_032L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "gemma2",
            contextLength = 2048,
            tags         = listOf("Google", "Gemma 2"),
        ),

        // MID (4–8 GB RAM) ─────────────────────────────────────────────────────
        ModelEntry(
            id           = "gemma3-4b-it-q4",
            displayName  = "Gemma 3 4B IT · Q4_K_M",
            description  = "Google Gemma 3 4B — best mid-range choice. Great Hindi/Indian language support.",
            downloadUrl  = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
            fileSizeBytes = 2_684_354_560L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma3",
            contextLength = 4096,
            tags         = listOf("Recommended", "Google", "Gemma 3"),
        ),

        ModelEntry(
            id           = "gemma3n-e4b-it-q4",
            displayName  = "Gemma 3n E4B IT · Q4_K_M",
            description  = "Google's efficient 4B model — same footprint, faster inference on mid-range phones.",
            downloadUrl  = "https://huggingface.co/bartowski/google_gemma-3n-E4B-it-GGUF/resolve/main/google_gemma-3n-E4B-it-Q4_K_M.gguf",
            fileSizeBytes = 2_684_354_560L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma3n",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3n", "Efficient"),
        ),

        ModelEntry(
            id           = "gemma2-2b-it-q4",
            displayName  = "Gemma 2 2B IT · Q4_K_M",
            description  = "Google Gemma 2 2B — compact and reliable. Good balance for mid-range phones.",
            downloadUrl  = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileSizeBytes = 1_708_582_752L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "gemma2",
            contextLength = 2048,
            tags         = listOf("Google", "Gemma 2", "Compact"),
        ),

        // LOW (3–4 GB RAM) ─────────────────────────────────────────────────────
        ModelEntry(
            id           = "gemma3n-e2b-it-q4",
            displayName  = "Gemma 3n E2B IT · Q4_K_M",
            description  = "Google's efficient 2B — ~1.3 GB, runs on budget phones with 3 GB RAM.",
            downloadUrl  = "https://huggingface.co/bartowski/google_gemma-3n-E2B-it-GGUF/resolve/main/google_gemma-3n-E2B-it-Q4_K_M.gguf",
            fileSizeBytes = 1_396_703_232L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.LOW,
            modelFamily  = "gemma3n",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3n", "Low RAM"),
        ),

        ModelEntry(
            id           = "gemma3-1b-it-q4",
            displayName  = "Gemma 3 1B IT · Q4_K_M",
            description  = "Google Gemma 3 1B — smallest Gemma. Only ~650 MB, works on entry-level phones.",
            downloadUrl  = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
            fileSizeBytes = 681_574_400L,
            engineType   = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.LOW,
            modelFamily  = "gemma3",
            contextLength = 4096,
            tags         = listOf("Google", "Gemma 3", "Smallest"),
        ),

        // ══ OTHER MODELS ══════════════════════════════════════════════════════

        // FLAGSHIP ─────────────────────────────────────────────────────────────
        ModelEntry(
            id          = "qwen2.5-7b-instruct-q4",
            displayName = "Qwen 2.5 7B · Q4_K_M",
            description = "Strong reasoning and multilingual. Great alternative on flagship phones.",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 4_683_074_240L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "qwen2.5",
            contextLength = 2048,
            tags        = listOf("Best Quality", "Multilingual"),
        ),

        ModelEntry(
            id          = "llama3.2-3b-instruct-q4",
            displayName = "Llama 3.2 3B · Q4_K_M",
            description = "Meta's compact Llama 3.2 — fast, good reasoning on flagship phones.",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 2_019_377_696L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.FLAGSHIP,
            modelFamily  = "llama3.2",
            contextLength = 2048,
            tags        = listOf("Fast", "Meta"),
        ),

        // MID ──────────────────────────────────────────────────────────────────
        ModelEntry(
            id          = "phi3.5-mini-instruct-q4",
            displayName = "Phi 3.5 Mini · Q4_K_M",
            description = "Microsoft Phi 3.5 — strong reasoning per parameter for mid-range devices.",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            fileSizeBytes = 2_393_232_672L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.MID,
            modelFamily  = "phi3.5",
            contextLength = 2048,
            tags        = listOf("Microsoft", "Strong Reasoning"),
        ),

        // LOW ──────────────────────────────────────────────────────────────────
        ModelEntry(
            id          = "llama3.2-1b-instruct-q4",
            displayName = "Llama 3.2 1B · Q4_K_M",
            description = "Smallest Llama 3.2 — only ~800 MB, runs on entry-level devices with 3 GB RAM.",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 807_694_464L,
            engineType  = EngineType.LLAMA_CPP,
            requiredTier = DeviceTier.LOW,
            modelFamily  = "llama3.2",
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

    /**
     * Returns models that fit this device — filtered by RAM tier AND available storage.
     * Storage gate: model must fit in 85% of free space (leaves headroom for extraction).
     * Result is ordered best-fit first (highest tier that fits within storage first).
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        val storageLimitMb = (profile.availableStorageMb * 0.85).toLong()
        return allModels.filter { model ->
            model.requiredTier.ordinal <= profile.tier.ordinal &&
            model.fileSizeBytes / 1_048_576 <= storageLimitMb
        }
    }

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    /**
     * Find the best LoRA adapter for [packType] given the currently loaded [modelFamily].
     * Returns null if no adapter exists yet (pack uses system-prompt-only mode).
     */
    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? =
        loraEntries.find { it.packType == packType && it.modelFamily == modelFamily }

    fun loraById(id: String): LoraEntry? = loraEntries.find { it.id == id }
}
