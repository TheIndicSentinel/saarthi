import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# 1. Add [JNI_ENTER] and [JNI_EXIT] around createConversation
jni_target = r'markStage\(CrashStage\.CREATE_CONVERSATION\)\s+activeConversation = newEngine\.createConversation\(ConversationConfig\(samplerConfig = samplerConfig\)\)'
jni_replace = r'''markStage(CrashStage.CREATE_CONVERSATION)
                    DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] createConversation (tokens=$effectiveMaxTokens, threads=$dynamicThreads, backend=${backendLabel()})")
                    try {
                        activeConversation = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_EXIT] createConversation SUCCESS")
                    } catch (e: Exception) {
                        DebugLogger.log("LITERT", "[JNI_ERROR] createConversation threw: ${e.message}")
                        throw e
                    } catch (t: Throwable) {
                        DebugLogger.log("LITERT", "[JNI_FATAL] createConversation threw Throwable: ${t.javaClass.simpleName}")
                        throw t
                    }'''
content = re.sub(jni_target, jni_replace, content)

# 2. Add [JNI_ENTER] and [JNI_EXIT] around sendMessageAsync (warmup)
warmup_target = r'activeConversation\?\.sendMessageAsync\(" ", object : MessageCallback \{'
warmup_replace = r'''DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] sendMessageAsync (warmup)")
                        activeConversation?.sendMessageAsync(" ", object : MessageCallback {'''
content = re.sub(warmup_target, warmup_replace, content)

# 3. Log native library info on init
# We'll insert this at the start of the initialize function
init_start = r'fun initialize\(config: ModelConfig\) \{'
init_info = r'''fun initialize(config: ModelConfig) {
        DebugLogger.log("LITERT", "[DEBUG] Native ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        DebugLogger.log("LITERT", "[DEBUG] SDK Version: ${android.os.Build.VERSION.SDK_INT}")'''
content = content.replace(init_start, init_info)

# 4. Add more recovery rule: Token = 64 as absolute floor if crash count >= 3
token_logic_target = r'effectiveMaxTokens: Int = run \{'
token_logic_replace = r'''effectiveMaxTokens: Int = run {
                    val floorTokens = if (cpuCrashCount >= 2) 64 else 256'''
content = content.replace(token_logic_target, token_logic_target + "\n                    // Debug Floor")

# Actually let's just modify the when cases
content = content.replace("cpuCrashCount >= 1 -> {", "cpuCrashCount >= 2 -> {\n                            DebugLogger.log(\"LITERT\", \"[TOKENS] maxTokens=64 (ULTRA-SAFE DEBUG MODE)\")\n                            64\n                        }\n                        cpuCrashCount >= 1 -> {")

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
