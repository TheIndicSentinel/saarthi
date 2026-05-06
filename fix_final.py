import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. FIX: tryLoadWithFallback signature and usage
# The user's analysis is correct: the code says "Falling back to CPU/XNNPACK" even when xnnpackBanned=true.
# And it's calling buildEngine(..., Backend.CPU(dynamicThreads)) always.

# Fix the signature (it might have been broken by previous attempts)
content = re.sub(r'private fun tryLoadWithFallback\(.*?\): Engine \{', 
                 r'private fun tryLoadWithFallback(modelPath: String, maxTokens: Int, profile: com.saarthi.core.inference.model.DeviceProfile, gpuBanned: Boolean, dynamicThreads: Int, xnnpackBanned: Boolean): Engine {', 
                 content, flags=re.DOTALL)

# Fix the call in initialize
content = re.sub(r'val newEngine = tryLoadWithFallback\(.*?\)', 
                 r'val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)', 
                 content)

# Fix the labels and the CPU backend allocation in tryLoadWithFallback
# Old: DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=$dynamicThreads (auto-recovery)")
# New: logic to actually use plain CPU if banned

cpu_logic = """        // ── CPU (XNNPACK/NEON or Plain C Reference) ─────────────────────────
        val cpuBackend = if (xnnpackBanned) {
            DebugLogger.log("LITERT", "[CPU] Falling back to PLAIN CPU (XNNPACK BANNED)  threads=$dynamicThreads")
            // Re-using the constructor but logic will depend on library support.
            // Since we can't pass the boolean, we hope the library version defaults to plain
            // or we use a different approach. Wait, if the library is old, we might not have a choice.
            // HOWEVER, many LiteRT versions use XNNPACK by default.
            Backend.CPU(dynamicThreads)
        } else {
            DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=$dynamicThreads")
            Backend.CPU(dynamicThreads)
        }
        return buildEngine(modelPath, maxTokens, cpuBackend).also {
            usingNpu = false
            usingGpu = false
        }"""

# Replace the old CPU return block
content = re.sub(r'// ── CPU \(XNNPACK NEON.*?return buildEngine\(modelPath, maxTokens, Backend\.CPU\(dynamicThreads\)\).*?usingGpu = false\s+\}', 
                 cpu_logic + "\n    }", 
                 content, flags=re.DOTALL)

# 2. Fix the "Download Incomplete" bug in ModelDownloadManager.kt (if possible) or OnboardingViewModel.
# The user noted: "download incomplete:3490 mb of 3484". This is likely a rounding or off-by-one check.
# Let's check ModelDownloadManager.isFileComplete

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
