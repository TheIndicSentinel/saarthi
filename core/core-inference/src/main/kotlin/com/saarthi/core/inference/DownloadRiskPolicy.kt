package com.saarthi.core.inference

/**
 * Whether to ask before starting a multi-hundred-MB model download.
 *
 * Field log: a 2.5 GB catalog download ran on cellular at 24% battery.
 * Confirm when the remaining transfer is large AND (metered/cellular OR
 * battery is below 30% while unplugged). Tiny Range-resumes skip the dialog.
 */
data class LargeDownloadConfirm(
    val becauseCellular: Boolean,
    val becauseLowBattery: Boolean,
) {
    val shouldConfirm: Boolean get() = becauseCellular || becauseLowBattery

    companion object {
        val NONE = LargeDownloadConfirm(becauseCellular = false, becauseLowBattery = false)
    }
}

object DownloadRiskPolicy {
    const val LOW_BATTERY_PERCENT = 30
    const val LARGE_REMAINING_BYTES = 200L * 1024L * 1024L

    fun confirm(
        isCellularOrMetered: Boolean,
        batteryPercent: Int?,
        isCharging: Boolean,
        remainingBytes: Long,
    ): LargeDownloadConfirm {
        if (remainingBytes < LARGE_REMAINING_BYTES) return LargeDownloadConfirm.NONE
        val cellular = isCellularOrMetered
        val lowBattery = !isCharging &&
            batteryPercent != null &&
            batteryPercent in 0 until LOW_BATTERY_PERCENT
        return LargeDownloadConfirm(
            becauseCellular = cellular,
            becauseLowBattery = lowBattery,
        )
    }
}
