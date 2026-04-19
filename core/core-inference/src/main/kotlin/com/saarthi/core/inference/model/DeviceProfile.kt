package com.saarthi.core.inference.model

data class DeviceProfile(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    val abi: String,
) {
    val tier: DeviceTier get() = when {
        totalRamMb >= 8_000 && hasVulkan -> DeviceTier.FLAGSHIP
        totalRamMb >= 4_000              -> DeviceTier.MID
        else                             -> DeviceTier.LOW
    }
}

enum class DeviceTier { LOW, MID, FLAGSHIP }
