package com.saarthi.core.inference.engine

import android.os.ParcelFileDescriptor
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class LlamaCppInferenceEngine @Inject constructor() : InferenceEngine {

    private var contextHandle: Long = -1L
    private var config: InferenceConfig? = null
    private var modelPfd: ParcelFileDescriptor? = null

    override var isReady: Boolean = false
        private set

    private val nativeAvailable: Boolean by lazy { LlamaCppBridge.tryLoad() }

    // Context sizes to try in order — smaller = less KV-cache RAM
    private val CTX_LADDER = listOf(2048, 1024, 512, 256)

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        if (isReady) return@withContext
        if (!nativeAvailable) throw UnsupportedOperationException(
            "llama.cpp native library not found on this device."
        )

        val file = File(config.modelPath)
        if (!file.exists()) throw IllegalArgumentException(
            "Model file not found: ${config.modelPath}\n\nPlease re-download the model."
        )
        if (!file.canRead()) throw SecurityException(
            "Cannot read model file — storage permission may be needed."
        )

        // Validate GGUF magic bytes before invoking native to give a clear error
        if (config.modelPath.endsWith(".gguf", ignoreCase = true)) {
            val magic = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            val valid = magic.size == 4 &&
                magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() &&
                magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
            if (!valid) throw IllegalStateException(
                "Model file is corrupted or incomplete (invalid GGUF header).\n\n" +
                "Delete the file and re-download it."
            )
        }

        // Open via file descriptor — avoids scoped-storage path issues on Android 10+
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val fd  = pfd.fd

        Timber.d("Initialising llama.cpp fd=$fd  size=${file.length() / 1_048_576}MB  gpuLayers=${config.nGpuLayers}")

        // Try descending context sizes until one fits in RAM
        var handle = -1L
        var usedCtx = config.nCtx
        for (ctx in CTX_LADDER.filter { it <= config.nCtx } + listOf(CTX_LADDER.last())) {
            handle = LlamaCppBridge.nativeInitFd(
                fd         = fd,
                nCtx       = ctx,
                nThreads   = config.nThreads,
                nGpuLayers = config.nGpuLayers,
            )
            if (handle >= 0) {
                usedCtx = ctx
                break
            }
            Timber.w("Init failed at nCtx=$ctx, trying smaller context…")
        }

        if (handle < 0) {
            val nativeError = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
            pfd.close()
            val detail = if (nativeError.isNotBlank()) nativeError.trim().take(300) else
                "No details available. Try closing other apps to free RAM."
            throw RuntimeException("Model failed to load.\n\n$detail")
        }

        if (usedCtx < config.nCtx) {
            Timber.w("Loaded with reduced context nCtx=$usedCtx (requested ${config.nCtx}) due to RAM constraints")
        }

        modelPfd?.close()
        modelPfd      = pfd
        contextHandle = handle
        this@LlamaCppInferenceEngine.config = config.copy(nCtx = usedCtx)
        isReady       = true
        Timber.d("llama.cpp ready handle=$handle  nCtx=$usedCtx")
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
            if (!ok) Timber.w("LoRA adapter failed: $adapterPath")
        }

    override fun clearLoraAdapter() {
        if (!isReady) return
        LlamaCppBridge.nativeClearLoraAdapter(contextHandle)
    }

    override fun release() {
        if (contextHandle >= 0) {
            LlamaCppBridge.nativeRelease(contextHandle)
            contextHandle = -1L
        }
        modelPfd?.close()
        modelPfd = null
        isReady  = false
    }
}
