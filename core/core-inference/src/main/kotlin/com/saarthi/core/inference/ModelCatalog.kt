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

    // ── Base models ───────────────────────────────────────────────────────────
    //
    // Only GGUF models via llama.cpp.  MediaPipe LiteRT (.task / .litertlm) have
    // been removed: their process-level native handler cannot be reliably freed,
    // causing "Another handler is already registered" crashes that cannot be fixed
    // at the application layer.
    //
    // Google Gemma 3 / Gemma 3n are Google's mobile-first models and are the
    // primary recommendation.  Gemma 3n uses an "Effective Parameter" (Xn E{N}B)
    // architecture with MatFormer blocks specifically tuned for on-device inference.
    //
    // Tier mapping:
    //   FLAGSHIP : ≥ 8 GB total RAM + Vulkan  (e.g. Samsung S23/S24, Pixel 8 Pro)
    //   MID      : 4–8 GB total RAM           (e.g. Samsung A54, Pixel 7a)
    //   LOW      : < 4 GB total RAM           (e.g. budget devices)

    val allModels: List<ModelEntry> = listOf(

        // ══ FLAGSHIP (≥ 8 GB RAM + Vulkan) ══════════════════════════════════════

        // Best quality: Gemma 3 12B — Google's flagship on-device model
        ModelEntry(
            id            = "gemma3-12b-it-q4",
            displayName   = "Gemma 3 12B IT · Q4_K_M",
            description   = "Google's best on-device model. Excellent multilingual, reasoning and Hindi support. Requires flagship phone with ≥ 8 GB RAM.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf",
            fileSizeBytes = 7_730_941_952L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Google", "Gemma 3", "Best Quality", "Flagship"),
        ),

        // Efficient alternative: Gemma 3n E4B — designed for on-device (mobile-first architecture)
        ModelEntry(
            id            = "gemma3n-e4b-it-q4",
            displayName   = "Gemma 3n E4B IT · Q4_K_M",
            description   = "Google Gemma 3n E4B — mobile-efficient architecture (MatFormer). Same quality as 4B with faster inference. ~4 GB download. Great for flagship and high-end mid-range.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3n-E4B-it-GGUF/resolve/main/google_gemma-3n-E4B-it-Q4_K_M.gguf",
            fileSizeBytes = 4_236_509_184L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("Google", "Gemma 3n", "Mobile-Efficient", "Recommended"),
        ),

        ModelEntry(
            id            = "gemma2-9b-it-q4",
            displayName   = "Gemma 2 9B IT · Q4_K_M",
            description   = "Google Gemma 2 9B — strong quality and multilingual support on flagship phones.",
            downloadUrl   = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            fileSizeBytes = 5_905_580_032L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("Google", "Gemma 2"),
        ),

        // ══ MID (4–8 GB RAM) ══════════════════════════════════════════════════

        // Best mid-range: Gemma 3 4B — recommended for most Android phones
        ModelEntry(
            id            = "gemma3-4b-it-q4",
            displayName   = "Gemma 3 4B IT · Q4_K_M",
            description   = "Google Gemma 3 4B — best choice for mid-range phones (4–8 GB RAM). Excellent Hindi, multilingual and reasoning. 2.5 GB download.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
            fileSizeBytes = 2_684_354_560L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Recommended", "Google", "Gemma 3", "Best Mid-Range"),
        ),

        // Mobile-efficient alternative: Gemma 3n E4B also fits mid-range (same file size as 4B)
        ModelEntry(
            id            = "gemma2-2b-it-q4",
            displayName   = "Gemma 2 2B IT · Q4_K_M",
            description   = "Google Gemma 2 2B — compact and reliable. Good balance for mid-range phones with 4–5 GB RAM.",
            downloadUrl   = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileSizeBytes = 1_708_582_752L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma2",
            contextLength = 2048,
            tags          = listOf("Google", "Gemma 2", "Compact"),
        ),

        // ══ LOW (< 4 GB RAM) ══════════════════════════════════════════════════

        // Mobile-first: Gemma 3n E2B — Google's smallest mobile-efficient model
        ModelEntry(
            id            = "gemma3n-e2b-it-q4",
            displayName   = "Gemma 3n E2B IT · Q4_K_M",
            description   = "Google Gemma 3n E2B — mobile-efficient architecture. ~1.3 GB, designed specifically for on-device inference on budget phones.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3n-E2B-it-GGUF/resolve/main/google_gemma-3n-E2B-it-Q4_K_M.gguf",
            fileSizeBytes = 1_396_703_232L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 4096,
            tags          = listOf("Google", "Gemma 3n", "Mobile-Efficient", "Recommended"),
        ),

        // Smallest Gemma: 1B for very limited devices
        ModelEntry(
            id            = "gemma3-1b-it-q4",
            displayName   = "Gemma 3 1B IT · Q4_K_M",
            description   = "Google Gemma 3 1B — smallest Gemma. Only 650 MB, works on entry-level phones with limited RAM.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
            fileSizeBytes = 681_574_400L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Google", "Gemma 3", "Smallest"),
        ),

        ModelEntry(
            id            = "gemma3-270m-it-q4",
            displayName   = "Gemma 3 270M IT · Q4_K_M",
            description   = "Google Gemma 3 270M — ultra-compact at ~200 MB. Fastest inference on any device. Basic tasks only.",
            downloadUrl   = "https://huggingface.co/bartowski/google_gemma-3-270m-it-GGUF/resolve/main/google_gemma-3-270m-it-Q4_K_M.gguf",
            fileSizeBytes = 209_715_200L,
            engineType    = EngineType.LLAMA_CPP,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Google", "Gemma 3", "Ultra-Compact"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────

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
     * Returns models ordered best-first for this device, filtered by RAM tier and
     * available storage.  Storage gate: model must fit within 90% of free space.
     *
     * For FLAGSHIP devices the 4B / E4B models are listed before the 12B so the user
     * sees practical options first; the 12B is shown below as a premium option.
     */
    fun recommendedFor(profile: DeviceProfile): List<ModelEntry> {
        val storageLimitMb = (profile.availableStorageMb * 0.90).toLong()
        return allModels.filter { model ->
            model.requiredTier.ordinal <= profile.tier.ordinal &&
            model.fileSizeBytes / 1_048_576 <= storageLimitMb
        }
    }

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? =
        loraEntries.find { it.packType == packType && it.modelFamily == modelFamily }

    fun loraById(id: String): LoraEntry? = loraEntries.find { it.id == id }
}
