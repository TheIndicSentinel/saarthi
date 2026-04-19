package com.saarthi.core.inference

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.saarthi.core.inference.model.DeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun profile(): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val totalRamMb = memInfo.totalMem / 1_048_576
        val availRamMb = memInfo.availMem / 1_048_576

        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val vulkanVersion = getVulkanVersion()

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        return DeviceProfile(
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            hasVulkan = hasVulkan,
            vulkanVersion = vulkanVersion,
            abi = abi,
        )
    }

    private fun getVulkanVersion(): String? = runCatching {
        val pm = context.packageManager
        // Vulkan 1.1+ reported as feature flags on API 28+
        when {
            pm.hasSystemFeature("android.hardware.vulkan.version") -> {
                val v = pm.systemAvailableFeatures
                    .firstOrNull { it.name == "android.hardware.vulkan.version" }
                    ?.version ?: return@runCatching null
                "${(v shr 22) and 0x3FF}.${(v shr 12) and 0x3FF}.${v and 0xFFF}"
            }
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) -> "1.0"
            else -> null
        }
    }.getOrNull()
}
