package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class LlamaCppInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    @Volatile private var contextHandle: Long = -1L
    @Volatile private var config: InferenceConfig? = null
    private var modelPfd: ParcelFileDescriptor? = null

    @Volatile override var isReady: Boolean = false
        private set

    private val initMutex = Mutex()

    private val nativeAvailable: Boolean by lazy { LlamaCppBridge.tryLoad() }

    // Context sizes to try in order — smaller = less KV-cache RAM
    private val CTX_LADDER = listOf(2048, 1024, 512, 256)

    private fun availableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / 1_048_576
    }

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        // Mutex ensures only one initialization runs at a time — prevents race between
        // MainViewModel startup init and OnboardingViewModel manual init.
        initMutex.withLock {
            if (isReady) {
                if (config.modelPath == this@LlamaCppInferenceEngine.config?.modelPath) {
                    DebugLogger.log("INIT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                    return@withLock
                }
                Timber.d("Releasing existing llama.cpp state for model reload")
                release()
            }

            val ramMb = availableRamMb()
            val file = File(config.modelPath)
            if (!file.exists()) throw IllegalArgumentException("Model file not found: ${config.modelPath}")

            val fileSizeMb = file.length() / 1_048_576
            DebugLogger.log("INIT", "initialize()  path=${config.modelPath} size=${fileSizeMb}MB  avail=${ramMb}MB")

            if (ramMb < 400) {
                throw RuntimeException(
                    "Available RAM is critically low (${ramMb}MB).\n\n" +
                    "Close background apps and try again."
                )
            }

            if (!nativeAvailable) {
                throw UnsupportedOperationException("llama.cpp native library (libllama_bridge.so) not loaded!")
            }
            // Validate GGUF magic bytes
            if (config.modelPath.endsWith(".gguf", ignoreCase = true)) {
                val magic = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                val valid = magic.size == 4 && magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() &&
                            magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
                if (!valid) throw IllegalStateException("Model file is corrupted (invalid GGUF header).")
            }

            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd  = pfd.fd

            var handle = -1L
            var usedCtx = config.nCtx
            for (ctx in CTX_LADDER.filter { it <= config.nCtx } + listOf(CTX_LADDER.last())) {
                handle = LlamaCppBridge.nativeInitFd(
                    fd         = fd,
                    nCtx       = ctx,
                    nThreads   = config.nThreads,
                    nGpuLayers = config.nGpuLayers,
                )
                if (handle != -1L) {
                    usedCtx = ctx
                    break
                }
            }

            if (handle == -1L) {
                pfd.close()
                val nativeError = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
                throw RuntimeException("llama.cpp failed to load model: $nativeError")
            }

            modelPfd      = pfd
            contextHandle = handle
            this@LlamaCppInferenceEngine.config = config.copy(nCtx = usedCtx)
            isReady       = true
            Timber.d("llama.cpp ready handle=$handle  nCtx=$usedCtx")
            DebugLogger.log("INIT", "Model ready  handle=$handle  nCtx=$usedCtx")
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        if (!isReady) {
            close(IllegalStateException("Model not loaded. Please initialize the inference engine first."))
            return@callbackFlow
        }
        val cfg = config ?: run {
            close(IllegalStateException("Model not loaded. Please initialize the inference engine first."))
            return@callbackFlow
        }
        val handle = contextHandle
        DebugLogger.log("GENERATE", "Stream start  handle=$handle  maxTokens=${cfg.maxTokens}")
        var tokenCount = 0
        withContext(Dispatchers.IO) {
            try {
                LlamaCppBridge.nativeGenerateStream(
                    contextHandle = handle,
                    prompt        = prompt,
                    maxTokens     = cfg.maxTokens,
                    temperature   = cfg.temperature,
                    topK          = cfg.topK,
                    tokenCallback = object : LlamaCppBridge.TokenCallback {
                        override fun onToken(token: String): Boolean {
                            tokenCount++
                            trySend(token)
                            return !isClosedForSend
                        }
                    }
                )
            } catch (e: Throwable) {
                val msg = e.message?.takeIf { it.isNotBlank() }
                    ?: "Generation failed (${e.javaClass.simpleName})"
                DebugLogger.log("GENERATE", "Error: $msg")
                Timber.e(e, "LlamaCpp native stream generation failed")
                close(RuntimeException(msg, e))
                return@withContext
            }
        }
        DebugLogger.log("GENERATE", "Stream done  tokens=$tokenCount")
        if (tokenCount == 0) {
            DebugLogger.log("GENERATE", "WARNING: 0 tokens generated — model may be misloaded or prompt malformed")
        }
        close()
        awaitClose {}
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) {
            check(isReady) { "LlamaCppInferenceEngine not initialised." }
            val cfg = config!!
            try {
                LlamaCppBridge.nativeGenerate(
                    contextHandle = contextHandle,
                    prompt        = prompt,
                    maxTokens     = cfg.maxTokens,
                    temperature   = cfg.temperature,
                    topK          = cfg.topK,
                )
            } catch (e: Throwable) {
                val msg = e.message?.takeIf { it.isNotBlank() }
                    ?: "Generation failed (${e.javaClass.simpleName})"
                Timber.e(e, "LlamaCpp native generation failed")
                throw RuntimeException(msg, e)
            }
        }

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        withContext(Dispatchers.IO) {
            if (!isReady) return@withContext
            try {
                val ok = LlamaCppBridge.nativeLoadLoraAdapter(contextHandle, adapterPath, scale)
                if (!ok) Timber.w("LoRA adapter failed: $adapterPath")
            } catch (e: Exception) {
                Timber.e(e, "Native LoRA load failed")
            }
        }

    override fun clearLoraAdapter() {
        if (!isReady) return
        LlamaCppBridge.nativeClearLoraAdapter(contextHandle)
    }

    override fun release() {
        val h = contextHandle
        if (h != -1L) {
            LlamaCppBridge.nativeRelease(h)
            contextHandle = -1L
        }
        modelPfd?.close()
        modelPfd = null
        isReady  = false
    }
}
