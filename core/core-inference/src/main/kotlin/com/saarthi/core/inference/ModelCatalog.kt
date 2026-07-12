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
     * The single best model to auto-select when the user isn't asked to
     * choose (first-run onboarding) — the "Recommended"-tagged entry from
     * [recommendedFor], or the first safe entry if none carries that tag
     * (defensive fallback; every current tier has a "Recommended" entry).
     */
    fun autoPick(profile: DeviceProfile): ModelEntry? {
        val candidates = recommendedFor(profile)
        return candidates.firstOrNull { "Recommended" in it.tags } ?: candidates.firstOrNull()
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
    //   LOW      : 3.5–6 GB total RAM  → Compact 1B only
    //   MID      : 6–10 GB total RAM   → Compact 1B + Gemma 4 E2B + Gemma 3n E2B
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
            description   = "The quickest answers on your phone — great for everyday questions, farming and health tips, and writing letters.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            fileSizeBytes = 3_242_086_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Fastest", "Recommended"),
            socTarget     = SocFamily.QUALCOMM_SM8750,
            baseModelId   = "gemma4-e2b-it-litert",
        ),

        // ── Gemma 4 · Generic (all devices with GPU) ─────────────────────────
        ModelEntry(
            id            = "gemma4-e2b-it-litert",
            displayName   = "Gemma 4 · Recommended  🌟",
            description   = "The best all-round choice for most phones. Smart, helpful answers for government schemes, farming, health, and writing.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 2_583_085_056L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Recommended", "Best for most phones"),
        ),

        // ── Gemma 4 E4B · Generic (flagship, GPU required) ───────────────────
        ModelEntry(
            id            = "gemma4-e4b-it-litert",
            displayName   = "Gemma 4 · Best Quality  ✨",
            description   = "The most detailed answers for tricky questions on law, money, farming, and health. Works best on newer, high-end phones.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileSizeBytes = 3_758_096_384L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            contextLength = 8192,
            tags          = listOf("Best answers", "For high-end phones"),
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
            description   = "A balanced choice — quick, helpful answers for everyday questions on government schemes, farming, and daily life.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileSizeBytes = 3_655_827_456L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Balanced", "Everyday use"),
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
            description   = "Higher-quality, detailed answers for farming, health, and government schemes. Works best on high-end phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 4_405_655_031L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            contextLength = 8192,
            tags          = listOf("Detailed answers", "For high-end phones"),
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3 — LITERT-LM
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3 1B · Generic (any phone) ─────────────────────────────────
        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 · Compact & Fast  ⚡",
            description   = "A small, light option that works on almost any phone. Best for short, simple questions — not for knowledge packs like Kisan.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileSizeBytes = 584_417_280L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            contextLength = 4096,
            tags          = listOf("Smallest", "Works on any phone"),
        ),
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }
}
