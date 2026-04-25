#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
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

// Backend is process-global — init once, never free (matches llama.cpp lifecycle).
static std::once_flag g_backend_once;

static void llama_log_cb(ggml_log_level level, const char* text, void*) {
    if (level >= GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
        g_last_error += text;
    } else if (level >= GGML_LOG_LEVEL_WARN) {
        LOGI("[WARN] %s", text);
        g_last_error += text;
    } else {
        LOGI("%s", text);
    }
}

extern "C" {

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

    // Init backend exactly once per process lifetime.
    std::call_once(g_backend_once, []() {
        llama_log_set(llama_log_cb, nullptr);
        llama_backend_init();
        LOGI("llama_backend_init() called (once)");
    });

    char modelPath[64];
    snprintf(modelPath, sizeof(modelPath), "/proc/self/fd/%d", fd);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_model_load_from_file(modelPath, mparams);
    if (!model) {
        LOGE("Failed to load model from fd=%d  path=%s", fd, modelPath);
        return -1L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context  nCtx=%d  nThreads=%d", nCtx, nThreads);
        llama_model_free(model);
        return -1L;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    auto* lctx = new LlamaContext{model, ctx, vocab, nullptr};
    LOGI("Model loaded  handle=%p  nCtx=%d  nThreads=%d  nGpuLayers=%d",
         (void*)lctx, nCtx, nThreads, nGpuLayers);
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
        LOGE("Failed to load LoRA adapter");
        return JNI_FALSE;
    }

    float scaleF = scale;
    llama_set_adapters_lora(lctx->ctx, &adapter, 1, &scaleF);
    lctx->adapter = adapter;
    LOGI("LoRA adapter loaded, scale=%.2f", scaleF);
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
        LOGI("LoRA adapter cleared");
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
        LOGE("Tokenization failed  promptLen=%d", promptLen);
        return "";
    }
    tokens.resize(nTokens);
    LOGI("doGenerate  promptTokens=%d  maxTokens=%d  stream=%s",
         nTokens, maxTokens, (streamEnv ? "yes" : "no"));

    // Reset the KV cache fully (data=true) so stale state from a previous
    // inference cannot corrupt the current one.
    llama_memory_clear(llama_get_memory(ctx), true);

    // Prefill
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        LOGE("llama_decode failed on prefill  nTokens=%d", nTokens);
        return "";
    }
    LOGI("Prefill done — starting decode loop");

    // Sampler chain
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    char tokenBuf[256];
    bool aborted = false;

    for (int i = 0; i < maxTokens; ++i) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) {
            LOGI("EOG token at i=%d — generation complete", i);
            break;
        }

        int len = llama_token_to_piece(vocab, token, tokenBuf, (int)sizeof(tokenBuf) - 1, 0, false);
        if (len <= 0) {
            LOGE("token_to_piece returned %d at i=%d", len, i);
            break;
        }
        tokenBuf[len] = '\0';

        if (streamEnv && tokenCallback && onTokenMethod) {
            jstring jTok = streamEnv->NewStringUTF(tokenBuf);
            if (!jTok) {
                LOGE("NewStringUTF returned null at token %d — aborting stream", i);
                aborted = true;
                break;
            }

            jboolean cont = streamEnv->CallBooleanMethod(tokenCallback, onTokenMethod, jTok);
            streamEnv->DeleteLocalRef(jTok);

            // If the Kotlin side threw an exception, log it, clear it, and stop.
            // Do NOT call any further JNI functions with a pending exception — it
            // causes a fatal "JNI: pending exception" abort.
            if (streamEnv->ExceptionCheck()) {
                jthrowable ex = streamEnv->ExceptionOccurred();
                streamEnv->ExceptionClear();
                if (ex) {
                    jclass exCls = streamEnv->GetObjectClass(ex);
                    jmethodID getMessage = streamEnv->GetMethodID(
                        exCls, "getMessage", "()Ljava/lang/String;");
                    if (getMessage) {
                        jstring msg = (jstring)streamEnv->CallObjectMethod(ex, getMessage);
                        if (msg) {
                            const char* msgStr = streamEnv->GetStringUTFChars(msg, nullptr);
                            LOGE("JNI callback exception at token %d: %s", i, msgStr);
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
                LOGI("Kotlin callback returned false at token %d — stream cancelled", i);
                aborted = true;
                break;
            }
        } else {
            result.append(tokenBuf, len);
        }

        llama_batch nextBatch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, nextBatch) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        if (i > 0 && i % 50 == 0) {
            LOGI("Generation progress: %d tokens", i);
        }
    }

    llama_sampler_free(sampler);
    LOGI("doGenerate done  result_len=%d  aborted=%s",
         (int)(streamEnv ? 0 : result.size()), (aborted ? "yes" : "no"));
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

    if (lctx->adapter) llama_adapter_lora_free(lctx->adapter);
    llama_free(lctx->ctx);
    llama_model_free(lctx->model);
    delete lctx;
    // llama_backend_free() intentionally omitted — the backend is process-global
    // and must not be freed while other contexts may exist or while the app is
    // running.  It is freed automatically when the process exits.
    LOGI("LlamaContext released");
}

} // extern "C"
