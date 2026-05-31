package com.saarthi.core.inference

import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.ModelEntry
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
                model.id in socOptimisedBaseIds -> false
                else -> true
            }
            isVisible && model.isSafeFor(profile)
        }
    }

    /**
     * Models one tier above the device's own tier that *might* still run
     * if the user has cleared background apps. Shown as an "advanced" option
     * where the UI supports it — never offered by default.
     */
    fun riskyFor(profile: DeviceProfile): List<ModelEntry> {
        val nextTierOrdinal = profile.tier.ordinal + 1
        return allModels.filter { model ->
            model.requiredTier.ordinal == nextTierOrdinal &&
            model.fileSizeMb <= profile.availableStorageMb - 200 &&
            model.socTarget == SocFamily.GENERIC
        }
    }

    // ── Model catalog ─────────────────────────────────────────────────────────
    //
    // All models use the .litertlm format (litertlm-android runtime, same as
    // Google AI Edge Gallery). Weights are memory-mapped from flash storage
    // by the LiteRT engine — the full file does NOT need to be RAM-resident.
    //
    // Architecture:
    //   • Each base model may have: one generic entry + one SoC-specific entry.
    //   • SoC-specific entries (socTarget != GENERIC) use QNN/Hexagon NPU.
    //   • Generic entries use GPU (OpenCL) with automatic CPU fallback.
    //   • recommendedFor(profile) selects the best entry per SoC automatically.
    //
    // Tier → models offered (isSafeFor gates on total-RAM tier, not availableRam):
    //   MINIMAL  : < 3.5 GB total RAM  → no model (insufficient for any LLM)
    //   LOW      : 3.5–6 GB total RAM  → Compact 1B + Gemma 3 4B (when live)
    //   MID      : 6–10 GB total RAM   → Compact 1B + Gemma 3 4B + Gemma 4 E2B + Gemma 3n E2B
    //   FLAGSHIP : ≥ 10 GB total RAM   → all models
    //
    // Target audience for India mid-range: MID tier covers the bulk of 6–8 GB
    // phones (OnePlus Nord, Realme GT, Samsung A54, Moto G84, Poco F5, etc.).
    // Gemma 4 E2B is the primary step-up from Compact for this segment —
    // 2.5 GB, 8192-token context, runs Kisan pack (Compact's 512-token limit
    // causes Kisan prompts to fail; E2B fixes that automatically).
    //
    // HuggingFace sources (Google licence required + HF token):
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
            contextLength = 8192,
            tags          = listOf("Best Quality", "New", "Powerful Phone Required"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3n — LITERT-LM (mobile-first MatFormer architecture)
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3n E2B · Generic (mid-range and above, 6 GB+ total RAM) ──────
        //
        // requiredTier=MID (was LOW — incorrect). At 3.5 GB, this model cannot
        // safely run on a LOW-tier device (3.5–6 GB total RAM): after OS + app
        // overhead, virtually no headroom remains for KV-cache and activations.
        // MID (6 GB+) is the correct minimum — the LiteRT mmap runtime
        // demand-pages weights from flash so the full 3.5 GB need not be
        // RAM-resident; only KV-cache + hot weight pages (~800 MB) are resident.
        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n · Fast & Smart  ⭐",
            description   = "Built for smartphones. Handles everyday questions on government schemes, farming, and local topics. ~3.5 GB download. For phones with 6 GB+ RAM.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileSizeBytes = 3_655_827_456L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Fast", "Smart", "6 GB+ RAM"),
        ),

        // ── Gemma 3n E4B · Generic (flagship only, 10 GB+ total RAM) ─────────
        //
        // requiredTier=FLAGSHIP (was MID — incorrect). At 4.2 GB, even with mmap
        // the resident footprint (KV-cache + activations + hot pages) exceeds
        // what a 6–10 GB MID device can sustain under typical OEM memory pressure.
        // FLAGSHIP (10 GB+) gives the headroom needed for stable generation.
        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n · High Quality  ⭐",
            description   = "Best quality for detailed farming, health, and government scheme questions. ~4.2 GB download. Requires a flagship phone (10 GB+ RAM).",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 4_405_655_031L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            contextLength = 8192,
            tags          = listOf("High Quality", "Flagship"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3 — LITERT-LM
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3 4B · Generic (budget mid-range and above, 4 GB+ total RAM) ─
        //
        // Fills the gap between the 1B Compact (557 MB, basic chat only) and
        // Gemma 4 E2B (2.5 GB, MID-tier minimum). At ~2.3 GB int4, the 4B model
        // runs on devices from 4 GB upward via mmap (hot pages + KV-cache ~700 MB
        // resident), giving LOW-tier phones real conversational and Kisan quality
        // without the MID-tier RAM requirement.
        //
        // ⚠️  URL NOT YET LIVE: Google has not published a Gemma 3 4B LiteRT-LM
        // bundle at the time of writing. The URL below follows the established
        // litert-community naming pattern (matches Gemma3-1B-IT exactly).
        // Verify at https://huggingface.co/litert-community/Gemma3-4B-IT before
        // shipping. Also confirm the exact filename and fileSizeBytes from the
        // HuggingFace file listing — update both fields when the model is released.
        ModelEntry(
            id            = "gemma3-4b-it-litert-int4",
            displayName   = "Gemma 3 4B · Balanced  🌟",
            description   = "Good everyday AI — handles farming questions, government schemes, and everyday advice. ~2.3 GB download. Works on phones with 4 GB+ RAM.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it-int4.litertlm",
            fileSizeBytes = 2_415_919_104L,   // ⚠️ estimate — verify exact bytes on HuggingFace
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,   // available from 3.5 GB+ devices (mmap)
            contextLength = 8192,
            tags          = listOf("Balanced", "Good for Most"),
        ),

        // ── Gemma 3 1B · Generic (any phone) ─────────────────────────────────
        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 · Compact & Fast  ⚡",
            description   = "Smallest AI model — quick answers for simple, everyday questions. ~584 MB download. Works on any Android phone.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileSizeBytes = 584_417_280L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            contextLength = 4096,
            tags          = listOf("Compact", "Fast", "Works on Any Phone"),
        ),
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }
}
