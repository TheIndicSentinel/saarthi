package com.saarthi.core.inference

import com.saarthi.core.inference.engine.gpuSafetyMarginMb
import com.saarthi.core.inference.model.SocFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gpuSafeForSoc] is the pure static-hardware GPU-eligibility decision
 * extracted from [DeviceProfiler.profile] (which needs a live Context/
 * ActivityManager and has no test coverage of its own). [classifySoc] is the
 * string→[SocFamily] classification, extracted for the same reason.
 *
 * The Path A change this pins down: MediaTek and Generic used to carry
 * their own RAM floor directly inside this decision (totalRamMb >= 8_000
 * for MediaTek, availRamMb >= 4_000 for Generic) — an unvalidated proxy for
 * "unproven driver" that duplicated, in a cruder form, what the load-time
 * memory-pressure margin gate in LiteRTInferenceEngine already does per
 * model. That floor is gone; [gpuSafeForSoc] no longer takes a RAM
 * parameter at all, by construction. The two "system-level impact" tests
 * below confirm the *safety* that floor was standing in for isn't gone — it
 * now comes from the model-aware margin gate everyone else was already
 * subject to.
 *
 * The Path B change: MediaTek's classification is now chip-identity-aware
 * (Dimensity 9000/9200/9300/9400 → MEDIATEK_FLAGSHIP) instead of one
 * substring bucket for the whole family — see [classifySoc]'s kdoc for what
 * that lookup does and does not claim.
 *
 * Point 5 Option B: SM8550 stays GPU-safe (try GPU for best throughput on a
 * high-range SoC; recover via crash ban). Do not regress to a static
 * CPU-only denylist — that would contradict low/mid/high “best backend”
 * selection. Exynos API 34+ remains the proactive GPU fail-closed case.
 */
class DeviceProfilerGpuSafetyTest {

    // ── No Vulkan vetoes every SoC family ────────────────────────────────

    @Test
    fun `no Vulkan blocks GPU regardless of SoC family`() {
        for (family in SocFamily.values()) {
            assertFalse(
                "SoC=$family should be blocked without Vulkan",
                gpuSafeForSoc(hasVulkan = false, socFamily = family, apiLevel = 30),
            )
        }
    }

    // ── MediaTek / Generic: the Path A change itself ─────────────────────

    @Test
    fun `MediaTek is GPU-safe with Vulkan — no RAM floor left to fail`() {
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK, apiLevel = 30))
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK, apiLevel = 34))
    }

    @Test
    fun `MediaTek flagship is also GPU-safe with Vulkan`() {
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK_FLAGSHIP, apiLevel = 30))
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK_FLAGSHIP, apiLevel = 34))
    }

    @Test
    fun `Generic SoC is GPU-safe with Vulkan — no RAM floor left to fail`() {
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.GENERIC, apiLevel = 30))
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.GENERIC, apiLevel = 34))
    }

    // ── Existing per-chip policy is unchanged by the extraction ──────────

    @Test
    fun `Qualcomm and Tensor families stay GPU-safe with Vulkan`() {
        listOf(
            SocFamily.QUALCOMM_SM8750,
            SocFamily.QUALCOMM_SM8550,
            SocFamily.QUALCOMM_GENERIC,
            SocFamily.GOOGLE_TENSOR,
        ).forEach { family ->
            assertTrue(
                "$family should be GPU-safe",
                gpuSafeForSoc(hasVulkan = true, socFamily = family, apiLevel = 30),
            )
        }
    }

    @Test
    fun `SM8550 stays GPU-safe across API levels — Point 5 Option B not CPU-only`() {
        // High-range SoC: attempt GPU for best model throughput. Residual
        // path faults use engine mitigations + 24h ban, not a static veto.
        // Contrast Exynos API 34+ below (proactive fail-closed).
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.QUALCOMM_SM8550, apiLevel = 30))
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.QUALCOMM_SM8550, apiLevel = 34))
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.QUALCOMM_SM8550, apiLevel = 35))
    }

    @Test
    fun `Exynos is GPU-safe below API 34 and blocked at API 34+`() {
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.SAMSUNG_EXYNOS, apiLevel = 33))
        assertFalse(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.SAMSUNG_EXYNOS, apiLevel = 34))
        assertFalse(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.SAMSUNG_EXYNOS, apiLevel = 35))
    }

    // ── System-level impact: does removing the static floor actually leave
    // a hole? These mirror the exact arithmetic LiteRTInferenceEngine runs
    // at load time (residentEstimateMb = sizeMb * 0.6; GPU is banned when
    // availableRamMb < residentEstimateMb + gpuSafetyMarginMb(totalRamMb)),
    // which unconditionally folds into gpuBanned regardless of gpuSafe.

    @Test
    fun `a MediaTek device below the old 8GB floor now passes gpuSafe, but real memory shortfall still bans GPU downstream`() {
        // Pre-Path A this device (totalRamMb=5000) was hard-blocked here
        // regardless of anything else, since 5000 < 8000.
        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK, apiLevel = 30))

        // Post-Path A it passes this gate — but a device that's genuinely
        // tight on free RAM is still caught by the load-time margin gate,
        // the same one every other SoC family answers to.
        val totalRamMb = 5_000L
        val availableRamMb = 1_800L
        val modelSizeMb = 2_500L
        val residentEstimateMb = (modelSizeMb * 6) / 10
        val margin = gpuSafetyMarginMb(totalRamMb)
        val memoryPressureBannedGpu = availableRamMb < residentEstimateMb + margin

        assertTrue(
            "expected the margin gate to ban GPU when real free RAM is this tight",
            memoryPressureBannedGpu,
        )
    }

    @Test
    fun `a MediaTek device below the old 8GB floor with genuinely spare RAM is now allowed through, where it used to be static-blocked`() {
        // Same sub-8GB device as above, but with real headroom this time —
        // this is the exact case Path A was meant to unblock: a capable
        // device that the old static totalRamMb>=8000 check vetoed purely
        // for being a MediaTek chip under a fixed spec-sheet number, with
        // no regard for how much RAM was actually free.
        val totalRamMb = 5_000L
        val availableRamMb = 4_200L
        val modelSizeMb = 2_500L
        val residentEstimateMb = (modelSizeMb * 6) / 10
        val margin = gpuSafetyMarginMb(totalRamMb)
        val memoryPressureBannedGpu = availableRamMb < residentEstimateMb + margin

        assertTrue(gpuSafeForSoc(hasVulkan = true, socFamily = SocFamily.MEDIATEK, apiLevel = 30))
        assertFalse(
            "a device with real headroom should not be banned by the margin gate either",
            memoryPressureBannedGpu,
        )
    }

    // ── classifySoc: the Path B chip-code lookup ─────────────────────────

    @Test
    fun `known Dimensity flagship chip codes classify as MEDIATEK_FLAGSHIP`() {
        // Real-world Build.SOC_MODEL / Build.BOARD strings are lowercase and
        // may carry extra suffixes (e.g. a board revision) around the code —
        // contains(), not equals(), is the correct match here.
        listOf("mt6983", "MT6983Z", "mt6985", "mt6989", "mt6991v/a").forEach { soc ->
            assertEquals(
                "expected $soc to classify as MEDIATEK_FLAGSHIP",
                SocFamily.MEDIATEK_FLAGSHIP,
                classifySoc(soc),
            )
        }
    }

    @Test
    fun `other MediaTek chips (older Dimensity, Helio, unrecognized) fall back to MEDIATEK`() {
        // mt6895=Dimensity 8100, mt6877=Dimensity 900, mt6768=Helio G85
        // (Helio SKUs use MTxxxx codes too, same as Dimensity) — none of
        // these are the flagship-tier codes classifySoc looks for.
        listOf("mt6895", "mt6877", "dimensity 700", "mt6768").forEach { soc ->
            assertEquals(
                "expected $soc to classify as MEDIATEK, not the flagship tier",
                SocFamily.MEDIATEK,
                classifySoc(soc),
            )
        }
    }

    @Test
    fun `classifySoc is unaffected for the non-MediaTek families`() {
        // Regression check: adding the flagship branch must not shift where
        // Qualcomm/Tensor/Exynos/unknown chips land.
        assertEquals(SocFamily.QUALCOMM_SM8750, classifySoc("SM8750"))
        assertEquals(SocFamily.QUALCOMM_SM8550, classifySoc("kalama"))
        assertEquals(SocFamily.QUALCOMM_GENERIC, classifySoc("SM7450"))
        assertEquals(SocFamily.GOOGLE_TENSOR, classifySoc("Tensor G3"))
        assertEquals(SocFamily.SAMSUNG_EXYNOS, classifySoc("Exynos 2400"))
        assertEquals(SocFamily.GENERIC, classifySoc("unisoc t616"))
    }
}
