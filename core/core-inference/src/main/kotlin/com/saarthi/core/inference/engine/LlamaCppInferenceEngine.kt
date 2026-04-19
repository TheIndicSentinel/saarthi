package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class LlamaCppInferenceEngine @Inject constructor() : InferenceEngine {

    private var contextHandle: Long = -1L
    private var config: InferenceConfig? = null

    override var isReady: Boolean = false
        private set

    private val nativeAvailable: Boolean by lazy { LlamaCppBridge.tryLoad() }

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        if (isReady) return@withContext
        if (!nativeAvailable) throw UnsupportedOperationException(
            "llama.cpp native library not found. " +
            "Run: git submodule update --init --recursive in core/core-inference/src/main/cpp"
        )
        Timber.d("Initialising llama.cpp: ${config.modelPath}  gpuLayers=${config.nGpuLayers}")
        val handle = LlamaCppBridge.nativeInit(
            modelPath  = config.modelPath,
            nCtx       = config.nCtx,
            nThreads   = config.nThreads,
            nGpuLayers = config.nGpuLayers,
        )
        if (handle < 0) throw RuntimeException(
            "llama.cpp failed to load model at ${config.modelPath}. " +
            "Make sure the file is a valid GGUF and the device has enough free RAM."
        )
        contextHandle = handle
        this@LlamaCppInferenceEngine.config = config
        isReady = true
        Timber.d("llama.cpp engine ready (handle=$handle)")
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        check(isReady) { "LlamaCppInferenceEngine not initialised." }
        val cfg = config!!
        withContext(Dispatchers.IO) {
            LlamaCppBridge.nativeGenerateStream(
                contextHandle = contextHandle,
                prompt        = prompt,
                maxTokens     = cfg.maxTokens,
                temperature   = cfg.temperature,
                topK          = cfg.topK,
                tokenCallback = object : LlamaCppBridge.TokenCallback {
                    override fun onToken(token: String): Boolean {
                        trySend(token)
                        return !isClosedForSend
                    }
                }
            )
        }
        close()
        awaitClose {}
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) {
            check(isReady) { "LlamaCppInferenceEngine not initialised." }
            val cfg = config!!
            LlamaCppBridge.nativeGenerate(
                contextHandle = contextHandle,
                prompt        = prompt,
                maxTokens     = cfg.maxTokens,
                temperature   = cfg.temperature,
                topK          = cfg.topK,
            )
        }

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        withContext(Dispatchers.IO) {
            if (!isReady) return@withContext
            val ok = LlamaCppBridge.nativeLoadLoraAdapter(contextHandle, adapterPath, scale)
            if (!ok) Timber.w("LoRA adapter failed to load: $adapterPath")
            else Timber.d("LoRA adapter loaded: $adapterPath  scale=$scale")
        }

    override fun clearLoraAdapter() {
        if (!isReady) return
        LlamaCppBridge.nativeClearLoraAdapter(contextHandle)
        Timber.d("LoRA adapter cleared")
    }

    override fun release() {
        if (contextHandle >= 0) {
            LlamaCppBridge.nativeRelease(contextHandle)
            contextHandle = -1L
        }
        isReady = false
    }
}
