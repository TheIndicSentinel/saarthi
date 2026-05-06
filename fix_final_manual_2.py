import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Update initialize logic
init_search = 'val batteryGpuBanned = isLowBattery'
# Check if xnnpackBanned already exists (it shouldn't after git checkout, but let's be safe)
if 'val xnnpackBanned' not in content:
    init_replace = 'val batteryGpuBanned = isLowBattery\n                val xnnpackBanned = cpuCrashCount >= 2'
    content = content.replace(init_search, init_replace)

# 2. Update call
content = content.replace('tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads)',
                          'tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)')

# 3. Update signature
content = content.replace('private fun tryLoadWithFallback(\n        modelPath: String,\n        maxTokens: Int,\n        profile: com.saarthi.core.inference.model.DeviceProfile,\n        gpuBanned: Boolean,\n        dynamicThreads: Int,\n    ): Engine',
                          'private fun tryLoadWithFallback(\n        modelPath: String,\n        maxTokens: Int,\n        profile: com.saarthi.core.inference.model.DeviceProfile,\n        gpuBanned: Boolean,\n        dynamicThreads: Int,\n        xnnpackBanned: Boolean,\n    ): Engine')

# 4. Update CPU labels
content = content.replace('DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=$dynamicThreads (auto-recovery)")',
                          'val backendLabel = if (xnnpackBanned) "PLAIN CPU (XNNPACK BANNED)" else "CPU/XNNPACK"\n        DebugLogger.log("LITERT", "[CPU] Falling back to $backendLabel  threads=$dynamicThreads (auto-recovery)")')

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
