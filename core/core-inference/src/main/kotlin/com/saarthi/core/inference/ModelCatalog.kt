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
        // SM8550 special case: GPU is banned, CPU crashes in createConversation(),
        // and NPU only works with QNN-compiled device-specific bundles.
        // Generic models have no safe execution path on SM8550 — hide them entirely.
        if (profile.socFamily == SocFamily.QUALCOMM_SM8550 && !profile.gpuSafe) {
            return allModels.filter {
                it.socTarget == SocFamily.QUALCOMM_SM8550 && it.isSafeFor(profile)
            }
        }

        val socOptimisedBaseIds = allModels
            .filter { it.socTarget == profile.socFamily && it.socTarget != SocFamily.GENERIC }
            .map { it.baseModelId }
            .toSet()

        return allModels.filter { model ->
            val isVisible = when {
                model.socTarget != SocFamily.GENERIC -> model.socTarget == profile.socFamily
                model.id in socOptimisedBaseIds -> false
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
    // All models use the .litertlm format required by litertlm-android:0.10.2.
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

        // ── Gemma 4 · Snapdragon 8 Gen 3 (SM8750 QNN/NPU optimised) ─────────
        ModelEntry(
            id            = "gemma4-e2b-it-qualcomm-sm8750",
            displayName   = "Gemma 4 · Fastest  ⚡",
            description   = "Optimised for your Snapdragon 8 Gen 3 processor. Fastest responses. Supports voice, text, and images. ~3.0 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            fileSizeBytes = 3_242_086_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 8192,
            tags          = listOf("Fastest", "New", "Snapdragon 8 Gen 3"),
            socTarget     = SocFamily.QUALCOMM_SM8750,
            baseModelId   = "gemma4-e2b-it-litert",
        ),

        // ── Gemma 4 · Generic (all devices with GPU) ─────────────────────────
        ModelEntry(
            id            = "gemma4-e2b-it-litert",
            displayName   = "Gemma 4 · Recommended  🌟",
            description   = "Google's latest AI. Great for government schemes, farming advice, health tips, and letter writing. ~2.5 GB download.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 2_583_085_056L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma4-e2b",
            contextLength = 8192,
            tags          = listOf("Recommended", "New", "Best for Most Phones"),
        ),

        // ── Gemma 4 E4B · Generic (flagship, GPU required) ───────────────────
        ModelEntry(
            id            = "gemma4-e4b-it-litert",
            displayName   = "Gemma 4 · Best Quality  ✨",
            description   = "Deepest knowledge for complex questions on law, farming, finance, and health. ~3.5 GB download. Requires a powerful phone.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileSizeBytes = 3_758_096_384L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            modelFamily   = "gemma4-e4b",
            contextLength = 8192,
            tags          = listOf("Best Quality", "New", "Powerful Phone Required"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3n — LITERT-LM (mobile-first MatFormer architecture)
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3n E2B · Generic (mid-range and above) ─────────────────────
        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n · Fast & Smart  ⭐",
            description   = "Built for smartphones. Handles everyday questions on government schemes, farming, and local topics. ~3.0 GB download. Works on most mid-range phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileSizeBytes = 3_655_827_456L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3n",
            contextLength = 8192,
            tags          = listOf("Recommended", "Fast", "Works on Most Phones"),
        ),

        // ── Gemma 3n E4B · Generic (mid-range and above) ─────────────────────
        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n · High Quality  ⭐",
            description   = "Better quality for detailed questions. Ideal for farming, health, and government scheme queries. ~4.0 GB download. Mid-range and flagship phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 4_405_655_031L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            modelFamily   = "gemma3n",
            contextLength = 8192,
            tags          = listOf("High Quality", "Mid-Range & Above"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3 1B — LITERT-LM (ultra-compact, any phone)
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3 · Snapdragon 8 Gen 2 (SM8550 QNN/NPU optimised) ─────────
        ModelEntry(
            id            = "gemma3-1b-it-sm8550",
            displayName   = "Gemma 3 · Fastest  ⚡",
            description   = "Optimised for your Snapdragon 8 Gen 2 processor. Fastest responses on your device. ~658 MB download.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm",
            fileSizeBytes = 690_143_232L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Fastest", "Compact", "Snapdragon 8 Gen 2"),
            socTarget     = SocFamily.QUALCOMM_SM8550,
            baseModelId   = "gemma3-1b-it-litert-int4",
        ),

        // ── Gemma 3 · Generic (all devices with GPU) ─────────────────────────
        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 · Compact & Fast  ⚡",
            description   = "Smallest AI model — quick answers for simple, everyday questions. ~584 MB download. Works on any Android phone.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileSizeBytes = 584_417_280L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            modelFamily   = "gemma3",
            contextLength = 4096,
            tags          = listOf("Compact", "Fast", "Works on Any Phone"),
        ),
    )

    // ── LoRA adapters ─────────────────────────────────────────────────────────

    val loraEntries: List<LoraEntry> = emptyList()

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }

    fun loraForPack(packType: PackType, modelFamily: String): LoraEntry? = null

    fun loraById(id: String): LoraEntry? = null
}
