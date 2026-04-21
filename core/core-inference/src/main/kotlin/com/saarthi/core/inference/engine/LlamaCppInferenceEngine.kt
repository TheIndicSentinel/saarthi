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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class LlamaCppInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var contextHandle: Long = -1L
    private var config: InferenceConfig? = null
    private var modelPfd: ParcelFileDescriptor? = null

    override var isReady: Boolean = false
        private set

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
        if (isReady) return@withContext

        val ramMb = availableRamMb()
        DebugLogger.log("INIT", "initialize() called  modelPath=${config.modelPath}")
        DebugLogger.log("INIT", "availableRAM=${ramMb}MB  nCtx=${config.nCtx}  nThreads=${config.nThreads}  nGpuLayers=${config.nGpuLayers}")
        DebugLogger.log("INIT", "nativeAvailable=$nativeAvailable")

        if (!nativeAvailable) {
            DebugLogger.log("INIT", "FAIL: native library not loaded")
            throw UnsupportedOperationException("llama.cpp native library not found on this device.")
        }

        val file = File(config.modelPath)
        DebugLogger.log("INIT", "file.exists=${file.exists()}  file.canRead=${file.canRead()}  file.length=${file.length() / 1_048_576}MB  path=${file.absolutePath}")

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
            DebugLogger.log("INIT", "GGUF magic check: valid=$valid  bytes=${magic.joinToString(",") { "0x%02X".format(it) }}")
            if (!valid) throw IllegalStateException(
                "Model file is corrupted or incomplete (invalid GGUF header).\n\n" +
                "Delete the file and re-download it."
            )
        }

        // Open via file descriptor — avoids scoped-storage path issues on Android 10+
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val fd  = pfd.fd
        DebugLogger.log("INIT", "PFD opened  fd=$fd  procPath=/proc/self/fd/$fd")

        Timber.d("Initialising llama.cpp fd=$fd  size=${file.length() / 1_048_576}MB  gpuLayers=${config.nGpuLayers}")

        // Try descending context sizes until one fits in RAM
        var handle = -1L
        var usedCtx = config.nCtx
        for (ctx in CTX_LADDER.filter { it <= config.nCtx } + listOf(CTX_LADDER.last())) {
            DebugLogger.log("INIT", "Trying nCtx=$ctx …")
            handle = LlamaCppBridge.nativeInitFd(
                fd         = fd,
                nCtx       = ctx,
                nThreads   = config.nThreads,
                nGpuLayers = config.nGpuLayers,
            )
            val nativeErrSoFar = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
            DebugLogger.log("INIT", "nativeInitFd result=$handle  nativeErr=${nativeErrSoFar.take(200)}")
            if (handle >= 0) {
                usedCtx = ctx
                break
            }
            Timber.w("Init failed at nCtx=$ctx, trying smaller context…")
        }

        if (handle < 0) {
            val nativeError = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
            val ramAfter = availableRamMb()
            DebugLogger.log("INIT", "FAIL: all ctx sizes exhausted  nativeError=${nativeError.take(300)}  ramAfter=${ramAfter}MB")
            pfd.close()
            val detail = if (nativeError.isNotBlank()) nativeError.trim().take(300) else
                "No details available — availRAM was ${ramMb}MB before init. Try closing other apps."
            throw RuntimeException("Model failed to load.\n\n$detail\n\nDebug log: ${DebugLogger.path()}")
        }

        if (usedCtx < config.nCtx) {
            Timber.w("Loaded with reduced context nCtx=$usedCtx (requested ${config.nCtx}) due to RAM constraints")
        }

        modelPfd?.close()
        modelPfd      = pfd
        contextHandle = handle
        this@LlamaCppInferenceEngine.config = config.copy(nCtx = usedCtx)
        isReady       = true
        DebugLogger.log("INIT", "SUCCESS  handle=$handle  nCtx=$usedCtx  ramAfter=${availableRamMb()}MB")
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
