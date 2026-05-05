import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# Add markUnstable logic
unstable_logic = """                val cpuCrashCount = getCpuCrashCount(config.modelPath)
                if (cpuCrashCount >= 3) {
                    DebugLogger.log("LITERT", "[CRASH] Model marked UNSTABLE after $cpuCrashCount CPU crashes.")
                    throw RuntimeException("Model is unstable on this device. Please use a smaller model.")
                }"""

# Insert before batteryStatus logic
content = content.replace("val cpuCrashCount = getCpuCrashCount(config.modelPath)", unstable_logic)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
