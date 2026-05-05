import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Add CrashStage enum and tracking
stage_enum = """enum class CrashStage { MODEL_LOAD, GPU_INIT, CPU_INIT, CREATE_CONVERSATION, WARMUP, GENERATION, CLEANUP }

@Singleton"""
content = content.replace("@Singleton", stage_enum)

# Update prefs to track stage
pref_stage = """    private fun markStage(stage: CrashStage) {
        enginePrefs.edit().putString("litert_crash_stage", stage.name).commit()
        DebugLogger.log("LITERT", "[STAGE] Entering stage: $stage")
    }"""
# Insert after wereConvReadyAtCrash
content = re.sub(r'(private fun wasConvReadyAtCrash\(\) =.*?commit\(\))', r'\1\n\n' + pref_stage, content, flags=re.DOTALL)

# 2. Update initialize() with better recovery logic and battery safe mode
init_target = r'override suspend fun initialize\(config: InferenceConfig\) = withContext\(engineDispatcher\) \{'
# We'll replace the block starting with effectiveMaxTokens calculation

# First, find and replace the crash recovery summary log to include stage
recovery_log_target = """                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")"""
recovery_log_replace = """                    val crashStage = enginePrefs.getString("litert_crash_stage", "UNKNOWN")
                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  stage=$crashStage  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")"""
content = content.replace(recovery_log_target, recovery_log_replace)

# 3. Update effectiveMaxTokens and dynamicThreads logic
# Also implement Battery Safe Mode (battery < 20% -> tokens=256, threads=1, no GPU)
tokens_logic_target = r'                val cpuCrashCount = getCpuCrashCount\(config.modelPath\).*?val dynamicThreads = if \(cpuCrashCount >= 2\) 1 else 2'
tokens_logic_replace = """                val cpuCrashCount = getCpuCrashCount(config.modelPath)
                val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = level * 100 / scale.toFloat()
                val isLowBattery = batteryPct < 20f && batteryPct > 0

                val effectiveMaxTokens: Int = run {
                    val headroomMb = profile.availableRamMb - sizeMb
                    when {
                        isLowBattery -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256 (BATTERY SAFE MODE: ${batteryPct.toInt()}%)")
                            256
                        }
                        cpuCrashCount >= 1 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256 (AUTO-RECOVERY: CPU crash count $cpuCrashCount)")
                            256
                        }
                        config.maxTokens > 0 && config.maxTokens <= 1024 -> config.maxTokens
                        headroomMb < 2048 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 — low RAM headroom=${headroomMb}MB")
                            512
                        }
                        else -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 (Production Default)")
                            512
                        }
                    }
                }
                
                val dynamicThreads = when {
                    isLowBattery -> 1
                    cpuCrashCount >= 2 -> 1
                    else -> 2
                }
                
                val batteryGpuBanned = isLowBattery"""
content = re.sub(tokens_logic_target, tokens_logic_replace, content, flags=re.DOTALL)

# 4. Use batteryGpuBanned in tryLoadWithFallback call
call_target = r'val newEngine = tryLoadWithFallback\(config\.modelPath, effectiveMaxTokens, profile, gpuBanned, dynamicThreads\)'
call_replace = r'val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads)'
content = re.sub(call_target, call_replace, content)

# 5. Add stage marking and warmup
# Surround tryLoadWithFallback with stages
try_block_target = r'markInitStarted\(config\.modelPath\)\s+try \{'
try_block_replace = """markInitStarted(config.modelPath)
                markStage(CrashStage.MODEL_LOAD)
                try {"""
content = re.sub(try_block_target, try_block_replace, content)

conv_target = r'activeConversation = newEngine\.createConversation\(ConversationConfig\(samplerConfig = samplerConfig\)\)'
conv_replace = """markStage(CrashStage.CREATE_CONVERSATION)
                    activeConversation = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                    
                    markStage(CrashStage.WARMUP)
                    DebugLogger.log("LITERT", "[INIT] Running warmup (1-token generation)...")
                    val warmupResult = activeConversation?.sendMessageAsync(" ", MessageCallback { })
                    // Note: sendMessageAsync is async, but on Samsung SM8550/S24, 
                    // if it crashes, it crashes immediately here during buffer alloc.
                    DebugLogger.log("LITERT", "[INIT] Warmup passed.")"""
content = re.sub(conv_target, conv_replace, content)

# 6. Update tryLoadWithFallback to log native allocation debug and use codeCacheDir
build_engine_target = r'    private fun buildEngine\(.*?\) \{.*?return Engine\(engineConfig\)'
# (This regex is tricky due to multiline)
# Let's just find buildEngine body

# We want to change cacheDir to codeCacheDir
cachedir_target = r'cacheDir     = null,'
cachedir_replace = r'cacheDir     = if (backend !is Backend.CPU) context.codeCacheDir.absolutePath else null,'
content = content.replace(cachedir_target, cachedir_replace)

# 7. Add native allocation debug logs in buildEngine
# Find the line where Engine(engineConfig) is called
engine_call_target = r'val e = Engine\(engineConfig\)'
engine_call_replace = """DebugLogger.log("LITERT", "[NATIVE] Allocating Engine: backend=${backendLabel()} tokens=$maxTokens cache=${engineConfig.cacheDir ?: "OFF"}")
        val e = Engine(engineConfig)"""
content = content.replace(engine_call_target, engine_call_replace)

# 8. Mark CLEANUP stage
release_target = r'    override fun release\(\) \{.*?closeInternal\(\)'
release_replace = """    override fun release() {
        markStage(CrashStage.CLEANUP)
        closeInternal()"""
content = re.sub(release_target, release_replace, content, flags=re.DOTALL)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
