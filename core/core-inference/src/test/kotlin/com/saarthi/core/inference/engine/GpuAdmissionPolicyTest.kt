package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.PromptTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gpuSafetyMarginMb], [isLargeTier]/[isCompactTier],
 * [isGpuRestrictedToCompactOnLowTier], and [calculateEffectiveMaxTokens] are
 * the pure decision math behind the GPU-admission and token-ladder-tier
 * hardening passes: replacing the old flat "avail < 3000MB" veto and flat
 * per-tier margin with a continuously-scaled reserve, and replacing
 * name-matched tier classification ("1b"/"compact"/"gemma 4" substrings)
 * with ModelEntry.promptTier (data-driven — see ModelCatalog). Getting the
 * tier classification wrong reproduces exactly the field bugs this
 * project's history is full of (Gemma 4 E4B/E2B token starvation, Kisan RAG
 * failing on the compact model's old 512-token cap), so this is real,
 * load-bearing logic, not just a legibility improvement.
 *
 * These pure functions (plus the crash-persistence logic now in
 * CrashRecoveryStore) are what's unit-testable at all in this area — the
 * orchestration that calls them lives inside
 * LiteRTInferenceEngine.initialize(), which needs the native engine and has
 * no coverage (no Robolectric in this project).
 */
class GpuAdmissionPolicyTest {

    // ── gpuSafetyMarginMb: anchors must match the old, field-validated values ──

    @Test
    fun `at or below the LOW floor the margin is the flat 1800MB anchor`() {
        assertEquals(1_800L, gpuSafetyMarginMb(3_500L))
        assertEquals(1_800L, gpuSafetyMarginMb(2_000L))
        assertEquals(1_800L, gpuSafetyMarginMb(500L))
    }

    @Test
    fun `at the LOW-MID boundary the margin is exactly the 1400MB anchor`() {
        assertEquals(1_400L, gpuSafetyMarginMb(6_000L))
    }

    @Test
    fun `at or above the MID-FLAGSHIP boundary the margin floors at 1200MB and never drops further`() {
        assertEquals(1_200L, gpuSafetyMarginMb(10_000L))
        // Real field-log device (SM8550, ~11GB) the 1200MB anchor was
        // validated against — must land on the same floor, not something
        // extrapolated below it.
        assertEquals(1_200L, gpuSafetyMarginMb(11_044L))
        // A much larger device must not need MORE margin than the
        // validated 11GB case, nor less than the 1200MB floor.
        assertEquals(1_200L, gpuSafetyMarginMb(16_000L))
    }

    @Test
    fun `margin scales continuously between anchors instead of stepping`() {
        // 4750MB is the midpoint of the 3500-6000 LOW band: margin must sit
        // strictly between 1800 and 1400, not jump to either endpoint.
        val lowBandMid = gpuSafetyMarginMb(4_750L)
        assertTrue("expected strictly between 1400 and 1800, was $lowBandMid", lowBandMid in 1_401L..1_799L)

        // 8000MB is the midpoint of the 6000-10000 MID band: margin must
        // sit strictly between 1400 and 1200.
        val midBandMid = gpuSafetyMarginMb(8_000L)
        assertTrue("expected strictly between 1200 and 1400, was $midBandMid", midBandMid in 1_201L..1_399L)
    }

    @Test
    fun `a 6point1GB and a 9point9GB device no longer get the identical margin`() {
        // This was the concrete bug the flat 3-step lookup had: both of
        // these landed in MID and got the exact same 1400MB margin.
        val justAboveMid = gpuSafetyMarginMb(6_100L)
        val justBelowFlagship = gpuSafetyMarginMb(9_900L)
        assertTrue(
            "6.1GB ($justAboveMid) must get a larger margin than 9.9GB ($justBelowFlagship)",
            justAboveMid > justBelowFlagship,
        )
    }

    @Test
    fun `margin is monotonically non-increasing as total RAM grows`() {
        val samples = longArrayOf(3_000, 3_500, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000, 10_000, 12_000, 20_000)
        for (i in 1 until samples.size) {
            val prev = gpuSafetyMarginMb(samples[i - 1])
            val curr = gpuSafetyMarginMb(samples[i])
            assertTrue(
                "margin must never increase as totalRamMb grows: ${samples[i - 1]}MB->$prev vs ${samples[i]}MB->$curr",
                curr <= prev,
            )
        }
    }

    // ── isLargeTier / isCompactTier ──────────────────────────────────────────

    @Test
    fun `LARGE promptTier is always large tier regardless of size`() {
        assertTrue(isLargeTier(PromptTier.LARGE, sizeMb = 100L))
    }

    @Test
    fun `COMPACT promptTier is never large tier even if the file is huge`() {
        // The explicit classification wins — a genuinely miscatalogued huge
        // "compact" model isn't silently upgraded via the size fallback,
        // which only applies to STANDARD (unclassified) models.
        assertFalse(isLargeTier(PromptTier.COMPACT, sizeMb = 5_000L))
    }

    @Test
    fun `STANDARD promptTier falls back to the sizeMb heuristic for large tier`() {
        assertFalse(isLargeTier(PromptTier.STANDARD, sizeMb = 1_500L))
        assertTrue(isLargeTier(PromptTier.STANDARD, sizeMb = 1_501L))
    }

    @Test
    fun `COMPACT promptTier is always compact tier regardless of size`() {
        assertTrue(isCompactTier(PromptTier.COMPACT, sizeMb = 5_000L))
    }

    @Test
    fun `LARGE promptTier is never compact tier even if the file is tiny`() {
        assertFalse(isCompactTier(PromptTier.LARGE, sizeMb = 100L))
    }

    @Test
    fun `STANDARD promptTier falls back to the sizeMb heuristic for compact tier`() {
        assertFalse(isCompactTier(PromptTier.STANDARD, sizeMb = 700L))
        assertTrue(isCompactTier(PromptTier.STANDARD, sizeMb = 699L))
    }

    @Test
    fun `a model can never be classified as both large and compact tier`() {
        val allCombinations = listOf(
            PromptTier.LARGE to 50L,
            PromptTier.COMPACT to 5_000L,
            PromptTier.STANDARD to 100L,
            PromptTier.STANDARD to 1_000L,
            PromptTier.STANDARD to 5_000L,
        )
        for ((tier, sizeMb) in allCombinations) {
            assertFalse(
                "tier=$tier sizeMb=$sizeMb must not be both large and compact",
                isLargeTier(tier, sizeMb) && isCompactTier(tier, sizeMb),
            )
        }
    }

    // ── isGpuRestrictedToCompactOnLowTier ───────────────────────────────────

    @Test
    fun `LOW tier with a non-compact model is restricted to CPU`() {
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.LOW, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `MINIMAL tier with a non-compact model is restricted to CPU`() {
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MINIMAL, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `LOW tier with the compact model is NOT restricted`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.LOW, isLowRamDevice = false, isCompactModel = true,
            ),
        )
    }

    @Test
    fun `MID tier with a non-compact model is not restricted by tier alone`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MID, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `FLAGSHIP tier with a non-compact model is not restricted`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `isLowRamDevice widens the restriction to a MID-tier device with a non-compact model`() {
        // This is the whole point of folding isLowRamDevice in: it can only
        // ever ADD devices to the restriction, never remove them.
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MID, isLowRamDevice = true, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `isLowRamDevice never overrides a compact model back into restriction`() {
        // isLowRamDevice is additive on the tier/RAM axis, not a separate
        // veto that ignores the model classification.
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = true, isCompactModel = true,
            ),
        )
    }

    @Test
    fun `isLowRamDevice alone on FLAGSHIP tier still restricts a non-compact model`() {
        // A device the OS flags as low-RAM gets the conservative treatment
        // even if its raw totalRamMb happens to classify as FLAGSHIP.
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = true, isCompactModel = false,
            ),
        )
    }

    // ── calculateEffectiveMaxTokens (extracted from LiteRTInferenceEngine's
    // token-ladder run{} block as part of the C3 God-class-reduction pass) ──
    // Each case below traces to a specific field bug — see the function's
    // kdoc. Default args make every test isolate the ONE thing it's testing.

    private fun tokens(
        cpuCrashCount: Int = 0,
        isLargeTier: Boolean = false,
        isCompactTier: Boolean = false,
        configMaxTokens: Int = 0,
        headroomMb: Long = 5_000L,
        sizeMb: Long = 2_000L,
        residentEstimateMb: Long = 1_200L,
    ) = calculateEffectiveMaxTokens(
        cpuCrashCount, isLargeTier, isCompactTier, configMaxTokens, headroomMb, sizeMb, residentEstimateMb,
    )

    @Test
    fun `2 or more CPU crashes floors LARGE tier at 1536, not lower`() {
        // The bricked-E4B incident: dropping below this made every
        // generation fail with "Input token ids are too long", even a
        // one-word "Hi". Must never regress below 1536 for LARGE.
        assertEquals(1_536, tokens(cpuCrashCount = 2, isLargeTier = true, headroomMb = 10_000L))
        assertEquals(1_536, tokens(cpuCrashCount = 5, isLargeTier = true, headroomMb = 10_000L))
    }

    @Test
    fun `2 or more CPU crashes drops non-LARGE tier to the ultra-safe 64 floor`() {
        assertEquals(64, tokens(cpuCrashCount = 2, isLargeTier = false, headroomMb = 10_000L))
    }

    @Test
    fun `exactly 1 CPU crash uses the auto-recovery floor, less severe than ultra-safe`() {
        assertEquals(1_536, tokens(cpuCrashCount = 1, isLargeTier = true, headroomMb = 10_000L))
        assertEquals(256, tokens(cpuCrashCount = 1, isLargeTier = false, headroomMb = 10_000L))
    }

    @Test
    fun `a crash-recovery floor overrides the caller's configMaxTokens, not the other way round`() {
        // Real crash evidence must win over a caller-supplied override —
        // the ladder checks cpuCrashCount BEFORE configMaxTokens.
        assertEquals(1_536, tokens(cpuCrashCount = 2, isLargeTier = true, configMaxTokens = 3000, headroomMb = 10_000L))
    }

    @Test
    fun `caller override wins when there is no crash evidence`() {
        assertEquals(3_000, tokens(configMaxTokens = 3_000, headroomMb = 10_000L))
    }

    @Test
    fun `caller override outside the valid 1 to 4096 range is ignored, not passed through`() {
        assertEquals(1_024, tokens(configMaxTokens = 5_000, headroomMb = 3_000L)) // falls to STANDARD default
    }

    @Test
    fun `LARGE tier scales to 4096 only with real headroom to spare, threshold stricter for big files`() {
        // sizeMb < 3000 → threshold 2400
        assertEquals(4_096, tokens(isLargeTier = true, headroomMb = 2_400L, sizeMb = 2_000L))
        assertEquals(2_048, tokens(isLargeTier = true, headroomMb = 2_399L, sizeMb = 2_000L))
        // sizeMb >= 3000 (e.g. E4B) → stricter threshold 3400
        assertEquals(4_096, tokens(isLargeTier = true, headroomMb = 3_400L, sizeMb = 3_500L))
        assertEquals(2_048, tokens(isLargeTier = true, headroomMb = 3_399L, sizeMb = 3_500L))
    }

    @Test
    fun `LARGE tier never drops below 1536 regardless of how little headroom remains`() {
        assertEquals(1_536, tokens(isLargeTier = true, headroomMb = 0L))
        assertEquals(1_536, tokens(isLargeTier = true, headroomMb = 1_499L))
    }

    @Test
    fun `COMPACT tier fits the Kisan RAG prompt at 2048 even under low headroom`() {
        // The Kisan-pack field bug: 512 caused "Input token ids are too
        // long: 1484 >= 512" on every RAG-attached prompt. COMPACT must
        // never fall through to the generic low-headroom 512 branch.
        assertEquals(2_048, tokens(isCompactTier = true, headroomMb = 100L))
    }

    @Test
    fun `STANDARD tier (neither LARGE nor COMPACT) drops to 512 under low headroom`() {
        assertEquals(512, tokens(headroomMb = 2_047L))
    }

    @Test
    fun `STANDARD tier defaults to 1024 with adequate headroom`() {
        assertEquals(1_024, tokens(headroomMb = 2_048L))
    }

    @Test
    fun `a model can never be classified as both LARGE and COMPACT for this ladder`() {
        // Defensive: if a caller ever passes both true (should be
        // impossible per isLargeTier/isCompactTier's own mutual-exclusion
        // guarantee), LARGE's branches are checked first and win — verifies
        // the when-ordering doesn't silently do something else.
        assertEquals(1_536, tokens(isLargeTier = true, isCompactTier = true, headroomMb = 0L))
    }

    // ── isNpuEligible / isGpuEligible (the "BackendSelector" half of the
    // same extraction, from tryLoadWithFallback's two if-conditions) ──────

    @Test
    fun `NPU is eligible only when the SoC allows it, GPU isn't banned, and the model has QNN layers`() {
        assertTrue(isNpuEligible(npuSafe = true, gpuBanned = false, modelNpuCompatible = true))
    }

    @Test
    fun `NPU is not eligible when the SoC doesn't allow it, regardless of the other two`() {
        assertFalse(isNpuEligible(npuSafe = false, gpuBanned = false, modelNpuCompatible = true))
    }

    @Test
    fun `NPU is not eligible when banned, even if the SoC and model would otherwise allow it`() {
        // The ban is shared between GPU and NPU (see CrashRecoveryStore's
        // kdoc — "A prior GPU/NPU crash bans both for 24h").
        assertFalse(isNpuEligible(npuSafe = true, gpuBanned = true, modelNpuCompatible = true))
    }

    @Test
    fun `NPU is not eligible when the model file has no QNN layers for this SoC`() {
        assertFalse(isNpuEligible(npuSafe = true, gpuBanned = false, modelNpuCompatible = false))
    }

    @Test
    fun `GPU is eligible when SoC-safe and not banned`() {
        // Memory fit is NOT part of isGpuEligible — it is enforced earlier via
        // residentEstimate + gpuSafetyMargin and folded into gpuBanned.
        assertTrue(isGpuEligible(gpuSafe = true, gpuBanned = false))
    }

    @Test
    fun `GPU is not eligible when the SoC or driver isn't safe`() {
        assertFalse(isGpuEligible(gpuSafe = false, gpuBanned = false))
    }

    @Test
    fun `GPU is not eligible when banned`() {
        assertFalse(isGpuEligible(gpuSafe = true, gpuBanned = true))
    }

    @Test
    fun `GPU eligibility does not depend on a fake bytes-vs-tokens budget check`() {
        // Regression guard for the removed third clause:
        // (safeModelBudgetMb * 1_048_576L) >= maxTokens — unit-mismatched and
        // always true for real budgets. Eligibility must stay true here; RAM
        // pressure bans GPU via gpuBanned instead.
        assertTrue(isGpuEligible(gpuSafe = true, gpuBanned = false))
        assertFalse(isGpuEligible(gpuSafe = true, gpuBanned = true))
    }
}
