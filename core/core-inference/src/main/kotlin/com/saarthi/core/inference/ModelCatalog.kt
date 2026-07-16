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
    // downloadUrl is pinned to a specific commit SHA (not resolve/main) with a
    // matching expectedSha256 on each entry — verified once, right after a
    // fresh download completes (see ModelDownloadService). This is what makes
    // fileSizeBytes/expectedSha256 trustworthy: pinning to an immutable
    // revision means they can't silently drift out from under the app the way
    // a mutable `main` ref could. To refresh an entry (e.g. picking up a new
    // upstream revision deliberately): fetch
    //   https://huggingface.co/api/models/<repo>?blobs=true
    // read the top-level "sha" (commit) and the matching file's entry under
    // "siblings" (size + siblings[].lfs.sha256), then update downloadUrl
    // (.../resolve/<sha>/<file>), fileSizeBytes, and expectedSha256 together.
    // A 6-entry catalog that changes rarely doesn't warrant automating this.
    //

    val allModels: List<ModelEntry> = listOf(

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 4 — LITERT-LM
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 4 · Snapdragon 8 Gen 3 (SM8750 QNN/NPU optimised) ─────────
        // URL pinned to commit 9262660 (was resolve/main) + expectedSha256 set —
        // both looked up via HF's model-info API on 2026-07-17. Verifying
        // against that same lookup also caught fileSizeBytes drift beyond the
        // small rounding error this app already tolerated: this entry's real
        // size was 225.8MB SMALLER than the stale catalog value.
        ModelEntry(
            id            = "gemma4-e2b-it-qualcomm-sm8750",
            displayName   = "Gemma 4 · Fastest  ⚡",
            description   = "The quickest answers on your phone — great for everyday questions, farming and health tips, and writing letters.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/9262660a1676eed6d0c477ab1a86344430854664/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            fileSizeBytes = 3_016_294_400L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Fastest", "Recommended"),
            socTarget     = SocFamily.QUALCOMM_SM8750,
            baseModelId   = "gemma4-e2b-it-litert",
            expectedSha256 = "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e",
        ),

        // ── Gemma 4 · Generic (all devices with GPU) ─────────────────────────
        // Pinned to the same commit (9262660) as the SM8750 variant above —
        // both files live in the same HF repo revision.
        ModelEntry(
            id            = "gemma4-e2b-it-litert",
            displayName   = "Gemma 4 · Recommended  🌟",
            description   = "The best all-round choice for most phones. Smart, helpful answers for government schemes, farming, health, and writing.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/9262660a1676eed6d0c477ab1a86344430854664/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 2_588_147_712L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Recommended", "Best for most phones"),
            expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        ),

        // ── Gemma 4 E4B · Generic (flagship, GPU required) ───────────────────
        // Pinned to commit f7ad334. Real size was 94MB smaller than the stale
        // catalog value (looked up 2026-07-17, same pass as the entries above).
        ModelEntry(
            id            = "gemma4-e4b-it-litert",
            displayName   = "Gemma 4 · Best Quality  ✨",
            description   = "The most detailed answers for tricky questions on law, money, farming, and health. Works best on newer, high-end phones.",
            downloadUrl   = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f7ad3343bd6ebc9607f4dc3bc4f2398bd5749bc5/gemma-4-E4B-it.litertlm",
            fileSizeBytes = 3_659_530_240L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            contextLength = 8192,
            tags          = listOf("Best answers", "For high-end phones"),
            expectedSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
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
        // Pinned to commit c03b6f6 (2026-07-17 lookup) — real size matched the
        // catalog exactly, no drift for this one.
        ModelEntry(
            id            = "gemma3n-e2b-it-litert-int4",
            displayName   = "Gemma 3n · Fast & Smart  ⭐",
            description   = "A balanced choice — quick, helpful answers for everyday questions on government schemes, farming, and daily life.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/c03b6f60b8da6c5400b6838a2cf26420f80c0a01/gemma-3n-E2B-it-int4.litertlm",
            fileSizeBytes = 3_655_827_456L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.MID,
            contextLength = 8192,
            tags          = listOf("Balanced", "Everyday use"),
            expectedSha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6",
        ),

        // ── Gemma 3n E4B · Generic (flagship only, 10 GB+ total RAM) ─────────
        //
        // requiredTier=FLAGSHIP (was MID — incorrect). At 4.2 GB, even with mmap
        // the resident footprint (KV-cache + activations + hot pages) exceeds
        // what a 6–10 GB MID device can sustain under typical OEM memory pressure.
        // FLAGSHIP (10 GB+) gives the headroom needed for stable generation.
        // Pinned to commit 297ed75 (2026-07-17 lookup). The stale catalog value
        // here was the largest drift found across the whole catalog — the real
        // file is 513.9MB LARGER than what isSafeFor()'s storage gate believed,
        // meaning a borderline-storage device could have been offered this
        // model without actually having enough room. Fixed by this correction.
        ModelEntry(
            id            = "gemma3n-e4b-it-litert-int4",
            displayName   = "Gemma 3n · High Quality  ⭐",
            description   = "Higher-quality, detailed answers for farming, health, and government schemes. Works best on high-end phones.",
            downloadUrl   = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/297ed75955702dec3503e00c2c2ecbbf475300bc/gemma-3n-E4B-it-int4.litertlm",
            fileSizeBytes = 4_919_541_760L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.FLAGSHIP,
            contextLength = 8192,
            tags          = listOf("Detailed answers", "For high-end phones"),
            expectedSha256 = "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4",
        ),

        // ══════════════════════════════════════════════════════════════════════
        //  GEMMA 3 — LITERT-LM
        // ══════════════════════════════════════════════════════════════════════

        // ── Gemma 3 1B · Generic (any phone) ─────────────────────────────────
        // Pinned to commit 6d54daa (2026-07-17 lookup) — real size matched the
        // catalog exactly, no drift for this one.
        ModelEntry(
            id            = "gemma3-1b-it-litert-int4",
            displayName   = "Gemma 3 · Compact & Fast  ⚡",
            description   = "A small, light option that works on almost any phone. Best for short, simple questions — not for knowledge packs like Kisan.",
            downloadUrl   = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/6d54daa71cfbffba6b2843c08eeb1a27e7430bf0/gemma3-1b-it-int4.litertlm",
            fileSizeBytes = 584_417_280L,
            engineType    = EngineType.LITERT,
            requiredTier  = DeviceTier.LOW,
            contextLength = 4096,
            tags          = listOf("Smallest", "Works on any phone"),
            expectedSha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
        ),
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    fun findById(id: String): ModelEntry? = allModels.find { it.id == id }
}
