package com.saarthi.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRiskPolicyTest {

    private fun confirm(
        cellular: Boolean = false,
        batteryPercent: Int? = 80,
        charging: Boolean = false,
        remainingBytes: Long = 2_500_000_000L,
    ) = DownloadRiskPolicy.confirm(
        isCellularOrMetered = cellular,
        batteryPercent = batteryPercent,
        isCharging = charging,
        remainingBytes = remainingBytes,
    )

    @Test
    fun `wifi with healthy battery does not confirm`() {
        val result = confirm(cellular = false, batteryPercent = 80, charging = false)
        assertFalse(result.shouldConfirm)
    }

    @Test
    fun `cellular large download must confirm`() {
        val result = confirm(cellular = true, batteryPercent = 80)
        assertTrue(result.shouldConfirm)
        assertTrue(result.becauseCellular)
        assertFalse(result.becauseLowBattery)
    }

    @Test
    fun `unplugged 24 percent battery must confirm even on wifi`() {
        val result = confirm(cellular = false, batteryPercent = 24, charging = false)
        assertTrue(result.shouldConfirm)
        assertTrue(result.becauseLowBattery)
        assertFalse(result.becauseCellular)
    }

    @Test
    fun `charging at 24 percent does not confirm for battery`() {
        val result = confirm(cellular = false, batteryPercent = 24, charging = true)
        assertFalse(result.shouldConfirm)
    }

    @Test
    fun `30 percent is not low battery`() {
        val result = confirm(cellular = false, batteryPercent = 30, charging = false)
        assertFalse(result.shouldConfirm)
    }

    @Test
    fun `cellular plus low battery sets both reasons`() {
        val result = confirm(cellular = true, batteryPercent = 24, charging = false)
        assertTrue(result.becauseCellular)
        assertTrue(result.becauseLowBattery)
    }

    @Test
    fun `tiny resume remaining skips the dialog`() {
        val result = confirm(
            cellular = true,
            batteryPercent = 10,
            remainingBytes = DownloadRiskPolicy.LARGE_REMAINING_BYTES - 1,
        )
        assertEquals(LargeDownloadConfirm.NONE, result)
    }

    @Test
    fun `unknown battery does not confirm for battery alone`() {
        val result = confirm(cellular = false, batteryPercent = null, charging = false)
        assertFalse(result.shouldConfirm)
    }
}
