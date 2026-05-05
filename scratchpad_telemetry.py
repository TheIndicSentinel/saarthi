import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Update crash count logic to track CPU crashes separately
crash_count_target = """    private fun getCrashCount(modelPath: String) =
        enginePrefs.getInt("litert_crash_count_${modelKey(modelPath)}", 0)

    private fun incrementCrashCount(modelPath: String) {
        val count = getCrashCount(modelPath) + 1
        enginePrefs.edit().putInt("litert_crash_count_${modelKey(modelPath)}", count).commit()
        DebugLogger.log("LITERT", "Crash count for ${modelKey(modelPath)}: $count")
    }"""

crash_count_replace = """    private fun getCrashCount(modelPath: String) =
        enginePrefs.getInt("litert_crash_count_${modelKey(modelPath)}", 0)

    private fun getCpuCrashCount(modelPath: String) =
        enginePrefs.getInt("litert_cpu_crash_count_${modelKey(modelPath)}", 0)

    private fun incrementCrashCount(modelPath: String, wasGpuOrNpu: Boolean) {
        val key = modelKey(modelPath)
        val count = getCrashCount(modelPath) + 1
        val editor = enginePrefs.edit().putInt("litert_crash_count_$key", count)
        
        if (!wasGpuOrNpu) {
            val cpuCount = getCpuCrashCount(modelPath) + 1
            editor.putInt("litert_cpu_crash_count_$key", cpuCount)
            DebugLogger.log("LITERT", "Crash count for $key: $count (CPU count: $cpuCount)")
        } else {
            DebugLogger.log("LITERT", "Crash count for $key: $count")
        }
        editor.commit()
    }"""
content = content.replace(crash_count_target, crash_count_replace)

# 2. Update incrementCrashCount calls
inc_target1 = """                    if (crashWasThisModel) {
                        incrementCrashCount(config.modelPath)"""
inc_replace1 = """                    if (crashWasThisModel) {
                        incrementCrashCount(config.modelPath, wasGpuOrNpu)"""
content = content.replace(inc_target1, inc_replace1)

inc_target2 = """                    if (crashedDuringInit && !crashWasThisModel) incrementCrashCount(config.modelPath)"""
inc_replace2 = """                    if (crashedDuringInit && !crashWasThisModel) incrementCrashCount(config.modelPath, false)"""
content = content.replace(inc_target2, inc_replace2)

# 3. Update breakCrashLoopIfNeeded threshold
loop_target = """        val count = getCrashCount(modelPath)
        if (count >= 3) {"""
loop_replace = """        val count = getCrashCount(modelPath)
        if (count >= 4) {"""
content = content.replace(loop_target, loop_replace)

# 4. Update maxTokens and pass dynamic threads to tryLoadWithFallback
tokens_target = """                    val effectiveMaxTokens = when {
                        config.maxTokens > 0 -> config.maxTokens
                        // Always hard-cap at budget / 2 bytes (assuming ~2 bytes per token entry + overhead)
                        // but generally we just use 1024 or 512 as standard values.
                        headroomMb < 50 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256   headroom=${headroomMb}MB (CRITICAL)")
                            256
                        }
                        headroomMb < 150 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512   headroom=${headroomMb}MB (LOW)")
                            512
                        }
                        else -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1024  headroom=${headroomMb}MB  model=${sizeMb}MB")
                            1024
                        }
                    }"""

tokens_replace = """                    val cpuCrashCount = getCpuCrashCount(config.modelPath)
                    val effectiveMaxTokens = when {
                        config.maxTokens > 0 -> config.maxTokens
                        cpuCrashCount >= 2 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256 (AUTO-RECOVERY: CPU crash count $cpuCrashCount)")
                            256
                        }
                        cpuCrashCount >= 1 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 (AUTO-RECOVERY: CPU crash count $cpuCrashCount)")
                            512
                        }
                        headroomMb < 50 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256   headroom=${headroomMb}MB (CRITICAL)")
                            256
                        }
                        headroomMb < 150 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512   headroom=${headroomMb}MB (LOW)")
                            512
                        }
                        else -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1024  headroom=${headroomMb}MB  model=${sizeMb}MB")
                            1024
                        }
                    }
                    
                    val dynamicThreads = if (cpuCrashCount >= 2) 1 else 2"""
content = content.replace(tokens_target, tokens_replace)

# 5. Pass dynamicThreads to tryLoadWithFallback
# First, add it to tryLoadWithFallback signature
tryload_target = """    private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
    ): Engine {"""
tryload_replace = """    private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
        dynamicThreads: Int,
    ): Engine {"""
content = content.replace(tryload_target, tryload_replace)

# Update the call site in initialize()
call_target = """val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned)"""
call_replace = """val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned, dynamicThreads)"""
content = content.replace(call_target, call_replace)

# Use dynamicThreads in Backend.CPU inside tryLoadWithFallback
cpu_target = """        // ── CPU (XNNPACK NEON — guaranteed path on all ARM64 devices) ──────────
        DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=2 (forced to prevent concurrent mmap crash)")
        return buildEngine(modelPath, maxTokens, Backend.CPU(2))"""
cpu_replace = """        // ── CPU (XNNPACK NEON — guaranteed path on all ARM64 devices) ──────────
        DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=$dynamicThreads (auto-recovery)")
        return buildEngine(modelPath, maxTokens, Backend.CPU(dynamicThreads))"""
content = content.replace(cpu_target, cpu_replace)

# 6. Telemetry (TTFT and Tokens/sec)
# Find token generation loop in generateStream
gen_loop_target = """                        if (!nativeCallStarted) {
                            nativeCallStarted = true
                            DebugLogger.log("LITERT", "Native generate() entered...")
                        }

                        val result = conversation.sendMessageAsync(prompt, MessageCallback { msg: Message ->
                            generateMutex.launch {
                                tokenCount++
                                trySend(msg.text)
                            }
                        })"""

gen_loop_replace = """                        if (!nativeCallStarted) {
                            nativeCallStarted = true
                            DebugLogger.log("LITERT", "Native generate() entered...")
                        }
                        
                        var firstTokenTimeMs = -1L

                        val result = conversation.sendMessageAsync(prompt, MessageCallback { msg: Message ->
                            generateMutex.launch {
                                if (firstTokenTimeMs == -1L) {
                                    firstTokenTimeMs = System.currentTimeMillis()
                                    val ttft = firstTokenTimeMs - genStartTimeMs
                                    DebugLogger.log("LITERT", "[TELEMETRY] Time to First Token (TTFT): ${ttft}ms")
                                }
                                tokenCount++
                                trySend(msg.text)
                            }
                        })"""
content = content.replace(gen_loop_target, gen_loop_replace)

# Also log Tokens/sec at the end of generateStream
end_gen_target = """                DebugLogger.log("LITERT", "Stream complete  tokens=$tokenCount  duration=${elapsed/1000}s")
            } finally {"""
end_gen_replace = """                val decodeDurationMs = System.currentTimeMillis() - if (firstTokenTimeMs != -1L) firstTokenTimeMs else genStartTimeMs
                val tps = if (decodeDurationMs > 0 && tokenCount > 0) (tokenCount * 1000f) / decodeDurationMs else 0f
                DebugLogger.log("LITERT", "[TELEMETRY] Stream complete  tokens=$tokenCount  duration=${elapsed/1000}s  Tokens/sec: ${String.format("%.2f", tps)}")
            } finally {"""
content = content.replace(end_gen_target, end_gen_replace)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
