import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Add Watchdog Thread
watchdog_code = r'''
                    // Watchdog to catch non-returning JNI calls
                    val watchdogJob = scope.launch {
                        delay(15_000)
                        DebugLogger.log("LITERT", "[WATCHDOG] JNI call createConversation has exceeded 15s. Likely native hang or imminent silent crash.")
                    }
                    
                    try {
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] createConversation (tokens=$effectiveMaxTokens, threads=$dynamicThreads, backend=${backendLabel()})")
                        activeConversation = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                        watchdogJob.cancel()
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_EXIT] createConversation SUCCESS")
                    }'''

# Replace the previous JNI_ENTER block
content = re.sub(r'DebugLogger\.log\("LITERT", "\[NATIVE\] \[JNI_ENTER\] createConversation.*?\n\s+activeConversation = newEngine\.createConversation\(ConversationConfig\(samplerConfig = samplerConfig\)\)\s+DebugLogger\.log\("LITERT", "\[NATIVE\] \[JNI_EXIT\] createConversation SUCCESS"\)', 
                 watchdog_code, content, flags=re.DOTALL)

# 2. Add Memory Statistics Log
mem_stats = r'''
                    val runtime = Runtime.getRuntime()
                    val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                    val maxMem = runtime.maxMemory() / 1024 / 1024
                    DebugLogger.log("LITERT", "[DEBUG] JVM Memory: Used=${usedMem}MB, Max=${maxMem}MB")
                    DebugLogger.log("LITERT", "[DEBUG] Estimated KV-Cache Allocation: ${ (effectiveMaxTokens * 2 * 1024) / 1024 / 1024 } MB (approx)")
'''
# Insert before JNI_ENTER
content = content.replace('DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER]', mem_stats + '                    DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER]')

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
