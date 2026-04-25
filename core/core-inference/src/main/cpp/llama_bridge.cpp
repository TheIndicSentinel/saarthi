#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstdio>
#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model*         model   = nullptr;
    llama_context*       ctx     = nullptr;
    const llama_vocab*   vocab   = nullptr;
    llama_adapter_lora*  adapter = nullptr;
};

static std::string g_last_error;
static std::once_flag g_backend_once;

// File handle for writing native logs into saarthi_debug.log.
// Set via nativeSetDebugLogPath so native crash traces are visible
// alongside Kotlin logs — critical for diagnosing SIGSEGV crashes.
static FILE* g_debug_log = nullptr;
static std::mutex g_log_mutex;

static void native_log(bool is_error, const char* tag, const char* fmt, ...) {
    // Format the message
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    if (is_error) {
        LOGE("[%s] %s", tag, buf);
    } else {
        LOGI("[%s] %s", tag, buf);
    }

    // Write to saarthi_debug.log with a flush so data survives a crash.
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_debug_log) {
        fprintf(g_debug_log, "[NATIVE/%s] %s\n", tag, buf);
        fflush(g_debug_log);
    }
}

#define NLOG(tag, ...)  native_log(false, tag, __VA_ARGS__)
#define NLOGE(tag, ...) native_log(true,  tag, __VA_ARGS__)

static void llama_log_cb(ggml_log_level level, const char* text, void*) {
    if (level >= GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
        g_last_error += text;
        // Also write llama.cpp errors to file
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_debug_log) { fprintf(g_debug_log, "[NATIVE/LLAMA_ERR] %s", text); fflush(g_debug_log); }
    } else if (level >= GGML_LOG_LEVEL_WARN) {
        LOGI("[WARN] %s", text);
        g_last_error += text;
    } else {
        LOGI("%s", text);
    }
}

extern "C" {

// ─── Debug log path (call once after DebugLogger.init) ───────────────────────

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeSetDebugLogPath(
    JNIEnv* env, jobject, jstring pathJ) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_debug_log) { fclose(g_debug_log); g_debug_log = nullptr; }
    if (!pathJ) return;
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    g_debug_log = fopen(path, "a");  // append so we don't lose previous session data
    env->ReleaseStringUTFChars(pathJ, path);
    if (g_debug_log) {
        fprintf(g_debug_log, "[NATIVE/INIT] Native log routing active\n");
        fflush(g_debug_log);
    }
}

// ─── Last error ───────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeGetLastError(
    JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

// ─── Model init via file descriptor ──────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeInitFd(
    JNIEnv*, jobject,
    jint fd, jint nCtx, jint nThreads, jint nGpuLayers) {

    g_last_error.clear();

    std::call_once(g_backend_once, []() {
        llama_log_set(llama_log_cb, nullptr);
        llama_backend_init();
        NLOG("INIT", "llama_backend_init() called (once)");
    });

    char modelPath[64];
    snprintf(modelPath, sizeof(modelPath), "/proc/self/fd/%d", fd);
    NLOG("INIT", "nativeInitFd  fd=%d  nCtx=%d  nThreads=%d  nGpuLayers=%d", fd, nCtx, nThreads, nGpuLayers);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    NLOG("INIT", "Loading model...");
    llama_model* model = llama_model_load_from_file(modelPath, mparams);
    if (!model) {
        NLOGE("INIT", "Failed to load model  fd=%d", fd);
        return -1L;
    }
    NLOG("INIT", "Model loaded OK");

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;

    NLOG("INIT", "Creating context  nCtx=%d...", nCtx);
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        NLOGE("INIT", "Failed to create context  nCtx=%d  nThreads=%d", nCtx, nThreads);
        llama_model_free(model);
        return -1L;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    auto* lctx = new LlamaContext{model, ctx, vocab, nullptr};
    NLOG("INIT", "Context ready  handle=%p  nCtx=%d  nGpuLayers=%d", (void*)lctx, nCtx, nGpuLayers);
    return reinterpret_cast<jlong>(lctx);
}

// ─── LoRA adapter ────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeLoadLoraAdapter(
    JNIEnv* env, jobject,
    jlong handle, jstring adapterPathJ, jfloat scale) {

    if (handle == (jlong)-1) return JNI_FALSE;
    auto* lctx = reinterpret_cast<LlamaContext*>(handle);

    if (lctx->adapter) {
        llama_adapter_lora_free(lctx->adapter);
        lctx->adapter = nullptr;
    }

    const char* path = env->GetStringUTFChars(adapterPathJ, nullptr);
    llama_adapter_lora* adapter = llama_adapter_lora_init(lctx->model, path);
    env->ReleaseStringUTFChars(adapterPathJ, path);

    if (!adapter) {
        NLOGE("LORA", "Failed to load LoRA adapter");
        return JNI_FALSE;
    }

    float scaleF = scale;
    llama_set_adapters_lora(lctx->ctx, &adapter, 1, &scaleF);
    lctx->adapter = adapter;
    NLOG("LORA", "LoRA adapter loaded  scale=%.2f", scaleF);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeClearLoraAdapter(
    JNIEnv*, jobject, jlong handle) {

    if (handle == (jlong)-1) return;
    auto* lctx = reinterpret_cast<LlamaContext*>(handle);

    if (lctx->adapter) {
        llama_set_adapters_lora(lctx->ctx, nullptr, 0, nullptr);
        llama_adapter_lora_free(lctx->adapter);
        lctx->adapter = nullptr;
        NLOG("LORA", "LoRA adapter cleared");
    }
}

// ─── Generation ──────────────────────────────────────────────────────────────

static std::string doGenerate(
    LlamaContext* lctx,
    const char* promptStr,
    int maxTokens,
    float temperature,
    int topK,
    JNIEnv* streamEnv,
    jobject tokenCallback,
    jmethodID onTokenMethod) {

    llama_context*     ctx   = lctx->ctx;
    const llama_vocab* vocab = lctx->vocab;

    NLOG("GEN", "doGenerate start  maxTokens=%d  stream=%s", maxTokens, streamEnv ? "yes" : "no");

    // Tokenise
    int promptLen = (int)strlen(promptStr);
    std::vector<llama_token> tokens(promptLen + 64);
    int nTokens = llama_tokenize(vocab, promptStr, promptLen,
                                  tokens.data(), (int)tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, promptStr, promptLen,
                                  tokens.data(), (int)tokens.size(), true, true);
    }
    if (nTokens <= 0) {
        NLOGE("GEN", "Tokenization failed  promptLen=%d", promptLen);
        return "";
    }
    tokens.resize(nTokens);
    NLOG("GEN", "Tokenized  promptTokens=%d", nTokens);

    // Reset KV cache (data=true clears everything for a clean inference)
    NLOG("GEN", "Clearing KV cache...");
    llama_memory_clear(llama_get_memory(ctx), true);
    NLOG("GEN", "KV cache cleared");

    // Prefill
    NLOG("GEN", "Starting prefill  nTokens=%d...", nTokens);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        NLOGE("GEN", "llama_decode FAILED on prefill  nTokens=%d", nTokens);
        return "";
    }
    NLOG("GEN", "Prefill done — decode loop starting");

    // Sampler chain
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    char tokenBuf[256];
    bool aborted = false;
    int tokenCount = 0;

    for (int i = 0; i < maxTokens; ++i) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) {
            NLOG("GEN", "EOG at i=%d — done", i);
            break;
        }

        int len = llama_token_to_piece(vocab, token, tokenBuf, (int)sizeof(tokenBuf) - 1, 0, false);
        if (len <= 0) {
            NLOGE("GEN", "token_to_piece=%d at i=%d", len, i);
            break;
        }
        tokenBuf[len] = '\0';
        tokenCount++;

        if (streamEnv && tokenCallback && onTokenMethod) {
            jstring jTok = streamEnv->NewStringUTF(tokenBuf);
            if (!jTok) {
                NLOGE("GEN", "NewStringUTF null at token %d", i);
                aborted = true;
                break;
            }

            jboolean cont = streamEnv->CallBooleanMethod(tokenCallback, onTokenMethod, jTok);
            streamEnv->DeleteLocalRef(jTok);

            if (streamEnv->ExceptionCheck()) {
                jthrowable ex = streamEnv->ExceptionOccurred();
                streamEnv->ExceptionClear();
                if (ex) {
                    jclass exCls = streamEnv->GetObjectClass(ex);
                    jmethodID getMessage = streamEnv->GetMethodID(exCls, "getMessage", "()Ljava/lang/String;");
                    if (getMessage) {
                        jstring msg = (jstring)streamEnv->CallObjectMethod(ex, getMessage);
                        if (msg) {
                            const char* msgStr = streamEnv->GetStringUTFChars(msg, nullptr);
                            NLOGE("GEN", "JNI exception at token %d: %s", i, msgStr);
                            streamEnv->ReleaseStringUTFChars(msg, msgStr);
                            streamEnv->DeleteLocalRef(msg);
                        }
                    }
                    streamEnv->DeleteLocalRef(exCls);
                    streamEnv->DeleteLocalRef(ex);
                }
                aborted = true;
                break;
            }
            if (!cont) {
                NLOG("GEN", "Callback returned false at token %d — cancelled", i);
                aborted = true;
                break;
            }
        } else {
            result.append(tokenBuf, len);
        }

        NLOG("GEN", "Decode step i=%d...", i);
        llama_batch nextBatch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, nextBatch) != 0) {
            NLOGE("GEN", "llama_decode FAILED at token %d", i);
            break;
        }

        if (i == 0) NLOG("GEN", "First token decoded OK");
        if (i > 0 && i % 50 == 0) NLOG("GEN", "Progress: %d tokens", i);
    }

    llama_sampler_free(sampler);
    NLOG("GEN", "doGenerate done  tokenCount=%d  aborted=%s", tokenCount, aborted ? "yes" : "no");
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeGenerate(
    JNIEnv* env, jobject,
    jlong handle, jstring promptJ, jint maxTokens, jfloat temperature, jint topK) {

    auto* lctx = reinterpret_cast<LlamaContext*>(handle);
    const char* prompt = env->GetStringUTFChars(promptJ, nullptr);
    std::string result = doGenerate(lctx, prompt, maxTokens, temperature, topK,
                                     nullptr, nullptr, nullptr);
    env->ReleaseStringUTFChars(promptJ, prompt);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeGenerateStream(
    JNIEnv* env, jobject,
    jlong handle, jstring promptJ, jint maxTokens, jfloat temperature, jint topK,
    jobject tokenCallback) {

    auto* lctx = reinterpret_cast<LlamaContext*>(handle);
    const char* prompt = env->GetStringUTFChars(promptJ, nullptr);

    jclass    cbClass   = env->GetObjectClass(tokenCallback);
    jmethodID onTokenId = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");

    doGenerate(lctx, prompt, maxTokens, temperature, topK, env, tokenCallback, onTokenId);
    env->ReleaseStringUTFChars(promptJ, prompt);
}

// ─── Release ─────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeRelease(
    JNIEnv*, jobject, jlong handle) {

    if (handle == (jlong)-1) return;
    auto* lctx = reinterpret_cast<LlamaContext*>(handle);

    NLOG("RELEASE", "Releasing handle=%p", (void*)lctx);
    if (lctx->adapter) llama_adapter_lora_free(lctx->adapter);
    llama_free(lctx->ctx);
    llama_model_free(lctx->model);
    delete lctx;
    // llama_backend_free() intentionally omitted — backend is process-global,
    // freed automatically on process exit.
    NLOG("RELEASE", "Done");
}

} // extern "C"
