import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# Fix the trailing issue: likely a misplaced brace or extra brace at the end.
# Let's just make sure the last line is the closing brace of the class.
content = content.strip()
if not content.endswith("}"):
    content += "\n}"

# Fix the signature and body properly this time.
# We will search for the entire tryLoadWithFallback and replace it.

pattern = r'private fun tryLoadWithFallback\(.*?\): Engine \{.*?\}'
replacement = """private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
        dynamicThreads: Int,
        xnnpackBanned: Boolean,
    ): Engine {

        val modelNpuCompatible = isModelNpuOptimised(modelPath, profile)

        if (profile.npuSafe && !gpuBanned && modelNpuCompatible) {
            try {
                DebugLogger.log("LITERT", "[NPU] Trying QNN/Hexagon NPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.NPU(context.applicationInfo.nativeLibraryDir))
                    .also {
                        usingNpu = true
                        usingGpu = false
                        DebugLogger.log("LITERT", "[NPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[NPU] Failed: ${e.message?.take(120)}")
            }
        }

        if (profile.gpuSafe && !gpuBanned && (profile.safeModelBudgetMb * 1_048_576L) >= maxTokens.toLong()) {
            try {
                DebugLogger.log("LITERT", "[GPU] Trying OpenCL/Vulkan GPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.GPU())
                    .also {
                        usingNpu = false
                        usingGpu = true
                        DebugLogger.log("LITERT", "[GPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[GPU] Failed: ${e.message?.take(120)}")
            }
        }

        val backendLabel = if (xnnpackBanned) "PLAIN CPU (XNNPACK BANNED)" else "CPU/XNNPACK"
        DebugLogger.log("LITERT", "[CPU] Falling back to $backendLabel  threads=$dynamicThreads (auto-recovery)")
        return buildEngine(modelPath, maxTokens, Backend.CPU(dynamicThreads))
            .also {
                usingNpu = false
                usingGpu = false
            }
    }"""

content = re.sub(pattern, replacement, content, flags=re.DOTALL)

# Fix the call site in initialize
call_pattern = r'val newEngine = tryLoadWithFallback\(.*?\)'
call_replacement = r'val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)'
content = re.sub(call_pattern, call_replacement, content)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
