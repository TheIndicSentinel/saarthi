package com.saarthi.feature.onboarding.ui

import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isLikelyCpuOnly] drives the onboarding model picker's third status state
 * ("Runs in compatibility mode (slower)") — the model WILL load, but this
 * device is expected to run it on CPU rather than GPU. It must exactly
 * mirror LiteRTInferenceEngine's isGpuRestrictedToCompactOnLowTier gate (via
 * DeviceProfile.gpuSafe/tier/isLowRamDevice + ModelEntry.requiredTier) or the
 * picker's messaging drifts from what actually happens at load time.
 */
class OnboardingScreenGpuMessagingTest {

    private fun profile(
        totalRamMb: Long = 8_000,
        gpuSafe: Boolean = true,
        isLowRamDevice: Boolean = false,
    ) = DeviceProfile(
        totalRamMb = totalRamMb,
        availableRamMb = totalRamMb / 2,
        safeModelBudgetMb = totalRamMb / 2,
        availableStorageMb = 10_000,
        cpuCores = 8,
        recommendedThreads = 4,
        hasVulkan = true,
        vulkanVersion = "1.1.0",
        gpuSafe = gpuSafe,
        isLowRamDevice = isLowRamDevice,
        abi = "arm64-v8a",
        apiLevel = 34,
        manufacturer = "Google",
    )

    // ── Higher-priority states always win, regardless of GPU status ────────────

    @Test
    fun `already-downloaded models are never flagged compatibility mode`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 4_000), // LOW tier, would otherwise restrict
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = true,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `insufficient storage takes priority over the compatibility-mode message`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 4_000),
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = true,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `insufficient RAM takes priority over the compatibility-mode message`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 4_000),
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = true,
            ),
        )
    }

    @Test
    fun `a null device profile never shows the compatibility-mode message`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = null,
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    // ── gpuSafe=false always shows compatibility mode, any tier ────────────────

    @Test
    fun `gpuSafe false shows compatibility mode even on FLAGSHIP tier`() {
        assertTrue(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 12_000, gpuSafe = false),
                modelRequiredTier = DeviceTier.FLAGSHIP,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    // ── LOW/MINIMAL tier: compact-only, mirrors the engine's gate ───────────────

    @Test
    fun `LOW tier with a non-compact model shows compatibility mode`() {
        assertTrue(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 4_000), // LOW tier
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `LOW tier with the compact model does NOT show compatibility mode`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 4_000), // LOW tier
                modelRequiredTier = DeviceTier.LOW,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `MINIMAL tier with a non-compact model shows compatibility mode`() {
        assertTrue(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 2_000), // MINIMAL tier
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    // ── MID/FLAGSHIP tier: fine by default, no over-warning ─────────────────────

    @Test
    fun `MID tier with a non-compact model runs fine by default`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 8_000), // MID tier
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `FLAGSHIP tier with a non-compact model runs fine by default`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 12_000), // FLAGSHIP tier
                modelRequiredTier = DeviceTier.FLAGSHIP,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    // ── isLowRamDevice widens the restriction, mirroring the engine gate ───────

    @Test
    fun `isLowRamDevice widens compatibility mode to a MID-tier device with a non-compact model`() {
        assertTrue(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 8_000, isLowRamDevice = true), // MID tier
                modelRequiredTier = DeviceTier.MID,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }

    @Test
    fun `isLowRamDevice never overrides a compact model back into compatibility mode`() {
        assertFalse(
            isLikelyCpuOnly(
                deviceProfile = profile(totalRamMb = 12_000, isLowRamDevice = true), // FLAGSHIP tier
                modelRequiredTier = DeviceTier.LOW,
                isDownloaded = false,
                insufficientStorage = false,
                insufficientRam = false,
            ),
        )
    }
}
