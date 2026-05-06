import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# Re-implementing the watchdog and memory logic cleanly after checkout
watchdog_code = r'''
                    val runtime = Runtime.getRuntime()
                    val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                    val maxMem = runtime.maxMemory() / 1024 / 1024
                    DebugLogger.log("LITERT", "[DEBUG] JVM Memory: Used=${usedMem}MB, Max=${maxMem}MB")
                    DebugLogger.log("LITERT", "[DEBUG] Estimated KV-Cache Allocation: ${ (effectiveMaxTokens * 2 * 1024) / 1024 / 1024 } MB (approx)")

                    // Watchdog to catch non-returning JNI calls
                    val watchdogJob = scope.launch {
                        delay(15_000)
                        DebugLogger.log("LITERT", "[WATCHDOG] JNI call createConversation has exceeded 15s. Likely native hang or imminent silent crash.")
                    }

                    try {
                        markStage(CrashStage.CREATE_CONVERSATION)
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] createConversation (tokens=$effectiveMaxTokens, threads=$dynamicThreads, backend=${backendLabel()})")
                        activeConversation = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                        watchdogJob.cancel()
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_EXIT] createConversation SUCCESS")
                    }'''

# Find the marker stage and createConversation block
pattern = r'markStage\(CrashStage\.CREATE_CONVERSATION\)\s+activeConversation = newEngine\.createConversation\(ConversationConfig\(samplerConfig = samplerConfig\)\)\s+DebugLogger\.log\("LITERT", "\[NATIVE\] \[JNI_EXIT\] createConversation SUCCESS"\)'
content = re.sub(pattern, watchdog_code, content, flags=re.DOTALL)

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
