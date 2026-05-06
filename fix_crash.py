import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Update dynamicThreads logic to be more aggressive in recovery
threads_target = r'val dynamicThreads = when \{\s+isLowBattery -> 1\s+cpuCrashCount >= 2 -> 1\s+else -> 2\s+\}'
threads_replace = """val dynamicThreads = when {
                    isLowBattery -> 1
                    cpuCrashCount >= 1 -> 1 // Aggressive: 1 thread after first CPU crash
                    else -> 2
                }"""
content = re.sub(threads_target, threads_replace, content)

# 2. Add xnnpackBanned logic based on cpuCrashCount >= 2
xnnpack_logic = """                val xnnpackBanned = cpuCrashCount >= 2
                if (xnnpackBanned) {
                    DebugLogger.log("LITERT", "[CPU] XNNPACK banned due to consecutive crashes.")
                }"""
# Insert after batteryGpuBanned
content = content.replace("val batteryGpuBanned = isLowBattery", "val batteryGpuBanned = isLowBattery\n" + xnnpack_logic)

# 3. Update tryLoadWithFallback call and signature
call_target = r'val newEngine = tryLoadWithFallback\(config\.modelPath, effectiveMaxTokens, profile, gpuBanned \|\| batteryGpuBanned, dynamicThreads\)'
call_replace = r'val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)'
content = re.sub(call_target, call_replace, content)

sig_target = r'private fun tryLoadWithFallback\(\s+modelPath: String,\s+maxTokens: Int,\s+profile: com\.saarthi\.core\.inference\.model\.DeviceProfile,\s+gpuBanned: Boolean,\s+dynamicThreads: Int,\s+\): Engine'
sig_replace = """private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
        dynamicThreads: Int,
        xnnpackBanned: Boolean,
    ): Engine"""
content = re.sub(sig_target, sig_replace, content)

# 4. Implement XNNPACK toggle in tryLoadWithFallback
cpu_label_target = r'DebugLogger\.log\("LITERT", "\[CPU\] Falling back to CPU/XNNPACK  threads=\$dynamicThreads \(auto-recovery\)"\)'
cpu_label_replace = r'DebugLogger.log("LITERT", "[CPU] Falling back to CPU${if (xnnpackBanned) "" else "/XNNPACK"}  threads=$dynamicThreads (auto-recovery)")'
content = content.replace(cpu_label_target, cpu_label_replace)

cpu_call_target = r'return buildEngine\(modelPath, maxTokens, Backend\.CPU\(dynamicThreads\)\)'
cpu_call_replace = r'return buildEngine(modelPath, maxTokens, Backend.CPU(dynamicThreads, !xnnpackBanned))'
content = re.sub(cpu_call_target, cpu_call_replace, content)

# 5. Disable warmup in recovery mode
warmup_target = r'markStage\(CrashStage\.WARMUP\).*?DebugLogger\.log\("LITERT", "\[INIT\] Warmup passed\."\)'
warmup_replace = """if (cpuCrashCount == 0 && !gpuBanned) {
                        markStage(CrashStage.WARMUP)
                        DebugLogger.log("LITERT", "[INIT] Running warmup (1-token generation)...")
                        activeConversation?.sendMessageAsync(" ", object : MessageCallback {
                            override fun onMessage(m: Message) {}
                            override fun onDone() {}
                            override fun onError(e: Throwable) {}
                        })
                        DebugLogger.log("LITERT", "[INIT] Warmup passed.")
                    } else {
                        DebugLogger.log("LITERT", "[INIT] Skipping warmup in recovery mode.")
                    }"""
content = re.sub(warmup_target, warmup_replace, content, flags=re.DOTALL)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
