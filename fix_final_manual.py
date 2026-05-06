import re

def fix_engine():
    with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
        lines = f.readlines()
    
    content = "".join(lines)

    # 1. Update initialize with xnnpackBanned logic
    init_search = 'val batteryGpuBanned = isLowBattery'
    init_replace = 'val batteryGpuBanned = isLowBattery\n                val xnnpackBanned = cpuCrashCount >= 2'
    content = content.replace(init_search, init_replace)

    # 2. Update call to tryLoadWithFallback
    content = content.replace('tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads)',
                              'tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)')

    # 3. Update tryLoadWithFallback signature
    content = content.replace('private fun tryLoadWithFallback(\n        modelPath: String,\n        maxTokens: Int,\n        profile: com.saarthi.core.inference.model.DeviceProfile,\n        gpuBanned: Boolean,\n        dynamicThreads: Int,\n    ): Engine',
                              'private fun tryLoadWithFallback(\n        modelPath: String,\n        maxTokens: Int,\n        profile: com.saarthi.core.inference.model.DeviceProfile,\n        gpuBanned: Boolean,\n        dynamicThreads: Int,\n        xnnpackBanned: Boolean,\n    ): Engine')

    # 4. Update CPU fallback label
    content = content.replace('DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=$dynamicThreads (auto-recovery)")',
                              'val backendLabel = if (xnnpackBanned) "PLAIN CPU (XNNPACK BANNED)" else "CPU/XNNPACK"\n        DebugLogger.log("LITERT", "[CPU] Falling back to $backendLabel  threads=$dynamicThreads (auto-recovery)")')

    # 5. Fix Download Threshold
    with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/ModelDownloadManager.kt", "r") as f:
        dm_content = f.read()
    dm_content = dm_content.replace("val threshold = if (trustOS) 0.90 else 0.99", "val threshold = if (trustOS) 0.85 else 0.95")
    
    with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
        f.write(content)
    with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/ModelDownloadManager.kt", "w") as f:
        f.write(dm_content)

fix_engine()
