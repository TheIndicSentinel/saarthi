package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.saarthi.core.inference.DeviceProfiler
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import android.content.ComponentCallbacks2
import android.content.res.Configuration

class LlamaCppInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfiler: DeviceProfiler,
) : InferenceEngine, ComponentCallbacks2 {

    init {
        context.registerComponentCallbacks(this)
    }

    @Volatile private var contextHandle: Long = -1L
    @Volatile private var config: InferenceConfig? = null
    @Volatile private var modelPfd: ParcelFileDescriptor? = null

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
        private set

    private val _activeModelNameFlow = MutableStateFlow<String?>(null)
    override val activeModelNameFlow: Flow<String?> = _activeModelNameFlow.asStateFlow()

    @Volatile override var activeModelName: String? = null
        private set

    private val initMutex = Mutex()

    private val nativeAvailable: Boolean by lazy { LlamaCppBridge.tryLoad() }

    // Context sizes to try in order — smaller = less KV-cache RAM
    private val CTX_LADDER = listOf(2048, 1024, 512, 256)

    // Prevents Android from killing the process during heavy CPU prefill.
    // Without this, the OOM killer terminates mid-decode with no Kotlin exception.
    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saarthi:llamacpp_inference")
            .also { it.setReferenceCounted(false) }
    }

    // ── Vulkan crash detection ───────────────────────────────────────────────
    // A commit()-persisted flag survives process kill. If it's set on startup,
    // a GPU-accelerated generation caused the previous crash → fall back to CPU.
    private val enginePrefs: SharedPreferences
        get() = context.getSharedPreferences("llama_engine_prefs", Context.MODE_PRIVATE)

    private fun vulkanPreviouslyCrashed(): Boolean =
        enginePrefs.getBoolean("gpu_gen_pending", false) ||
        enginePrefs.getBoolean("vulkan_failed",   false)

    private fun markGpuGenerationStarted() =
        enginePrefs.edit().putBoolean("gpu_gen_pending", true).commit()  // synchronous write

    private fun markGpuGenerationEnded() =
        enginePrefs.edit().putBoolean("gpu_gen_pending", false).apply()

    private fun recordVulkanCrash() {
        DebugLogger.log("INIT", "Vulkan crash detected — disabling GPU for this device")
        enginePrefs.edit()
            .putBoolean("gpu_gen_pending", false)
            .putBoolean("vulkan_failed",   true)
            .commit()
    }

    private fun setReady(value: Boolean) {
        isReady = value
        _isReadyFlow.value = value
    }

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isReady) {
                if (config.modelPath == this@LlamaCppInferenceEngine.config?.modelPath) {
                    DebugLogger.log("INIT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                    return@withLock
                }
                Timber.d("Releasing existing llama.cpp state for model reload")
                release()
            }

            // ── Adaptive Hardware Profiling ──
            val profile = deviceProfiler.profile()
            val fileSizeMb = File(config.modelPath).length() / 1_048_576

            if (fileSizeMb > profile.safeModelBudgetMb) {
                throw OutOfMemoryError(
                    "This model (${fileSizeMb}MB) is too large for your device's current available memory.\n\n" +
                    "Max safe size: ${profile.safeModelBudgetMb}MB\n" +
                    "Available: ${profile.availableRamMb}MB\n\n" +
                    "Try closing background apps or selecting a smaller model."
                )
            }

            if (!nativeAvailable) {
                throw UnsupportedOperationException("llama.cpp native library (libllama_bridge.so) not loaded!")
            }
            runCatching { LlamaCppBridge.nativeSetDebugLogPath(DebugLogger.path()) }

            if (config.modelPath.endsWith(".gguf", ignoreCase = true)) {
                val magic = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                val valid = magic.size == 4 && magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() &&
                            magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
                if (!valid) throw IllegalStateException("Model file is corrupted (invalid GGUF header).")
            }

            // Open PFD as fallback for content-URIs; real-path init is preferred below.
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd  = pfd.fd
            val useRealPath = !config.modelPath.startsWith("/proc/self/fd/")

            val estimatedRamAfterLoadMb = ramMb - fileSizeMb
            val adaptiveMaxCtx = when {
                estimatedRamAfterLoadMb >= 3_000 -> config.nCtx
                estimatedRamAfterLoadMb >= 1_800 -> minOf(config.nCtx, 2048)
                estimatedRamAfterLoadMb >= 1_000 -> minOf(config.nCtx, 1024)
                else                             -> minOf(config.nCtx, 512)
            }
            if (adaptiveMaxCtx < config.nCtx) {
                DebugLogger.log("INIT", "RAM tight (${estimatedRamAfterLoadMb}MB after model) — nCtx ${config.nCtx}→$adaptiveMaxCtx")
            }

            var handle = -1L
            var usedCtx = adaptiveMaxCtx
            val ctxList = CTX_LADDER.filter { it <= adaptiveMaxCtx } + listOf(CTX_LADDER.last())

            val requestedGpuLayers = if (config.nGpuLayers > 0 && vulkanPreviouslyCrashed()) {
                recordVulkanCrash()
                DebugLogger.log("INIT", "Vulkan crash history — loading CPU-only")
                0
            } else {
                config.nGpuLayers
            }
            var usedGpuLayers = requestedGpuLayers

            for (ctx in ctxList) {
                handle = if (useRealPath) {
                    // Re-enabling mmap for real paths. This allows the OS to manage memory 
                    // pressure more efficiently than resident malloc allocations.
                    DebugLogger.log("INIT", "Using real-path init (mmap enabled): ${config.modelPath.substringAfterLast('/')}")
                    LlamaCppBridge.nativeInitFromPath(config.modelPath, ctx,
                        profile.recommendedThreads, requestedGpuLayers)
                } else {
                    LlamaCppBridge.nativeInitFd(fd = fd, nCtx = ctx,
                        nThreads = profile.recommendedThreads, nGpuLayers = requestedGpuLayers)
                }
                if (handle != -1L) { usedCtx = ctx; break }
            }

            if (handle == -1L && requestedGpuLayers > 0) {
                val gpuError = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
                DebugLogger.log("INIT", "GPU init failed ($gpuError) — retrying CPU-only")
                usedGpuLayers = 0
                for (ctx in ctxList) {
                    handle = if (useRealPath) {
                        LlamaCppBridge.nativeInitFromPath(config.modelPath, ctx,
                            profile.recommendedThreads, 0)
                    } else {
                        LlamaCppBridge.nativeInitFd(fd = fd, nCtx = ctx,
                            nThreads = profile.recommendedThreads, nGpuLayers = 0)
                    }
                    if (handle != -1L) { usedCtx = ctx; break }
                }
            }

            if (handle == -1L) {
                pfd.close()
                val nativeError = runCatching { LlamaCppBridge.nativeGetLastError() }.getOrDefault("")
                throw RuntimeException("llama.cpp failed to load model: $nativeError")
            }

            val gpuMode = if (usedGpuLayers > 0) "Vulkan GPU layers=$usedGpuLayers" else "CPU-only"
            modelPfd      = pfd
            contextHandle = handle
            this@LlamaCppInferenceEngine.config = config.copy(nCtx = usedCtx, nGpuLayers = usedGpuLayers)
            setReady(true)
            DebugLogger.log("INIT", "Model ready  handle=$handle  nCtx=$usedCtx  $gpuMode")
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        val cfg = config
            ?: throw IllegalStateException("Model not loaded. Please initialize the inference engine first.")
        val handle = contextHandle
        if (handle == -1L) throw IllegalStateException("Engine not initialized.")

        val usingGpu = cfg.nGpuLayers > 0
        if (usingGpu) markGpuGenerationStarted()

        runCatching { wakeLock.acquire(10 * 60 * 1000L) }

        // CRITICAL: maxTokens must never exceed nCtx. If it does, llama.cpp tries to decode
        // past the end of the KV-cache buffer → immediate SIGSEGV / native crash.
        // Leave a 256-token safety margin for the prompt itself.
        val safeMaxTokens = cfg.maxTokens.coerceAtMost(cfg.nCtx - 256).coerceAtLeast(128)
        DebugLogger.log("GENERATE", "Stream start (real-time)  handle=$handle  maxTokens=$safeMaxTokens (nCtx=${cfg.nCtx})  gpu=$usingGpu")

        // Capture ProducerScope so the anonymous object can reference isActive/trySend.
        // Inside an object expression the outer 'this' (ProducerScope) is shadowed.
        val producer = this
        val callback = object : LlamaCppBridge.TokenCallback {
            override fun onToken(token: String): Boolean {
                if (!producer.isActive) return false
                return producer.trySend(token).isSuccess
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                LlamaCppBridge.nativeGenerateStream(
                    contextHandle = handle,
                    prompt        = prompt,
                    maxTokens     = safeMaxTokens,
                    temperature   = cfg.temperature,
                    topK          = cfg.topK,
                    tokenCallback = callback,
                )
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: "Generation failed (${e.javaClass.simpleName})"
                    DebugLogger.log("GENERATE", "Error: $msg")
                    close(RuntimeException(msg, e))
                    return@launch
                }
            } finally {
                if (usingGpu) markGpuGenerationEnded()
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
            close()
        }

        awaitClose {
            LlamaCppBridge.nativeCancelGeneration(handle)
            job.cancel()
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            DebugLogger.log("GENERATE", "Stream cancelled — native cancel signalled")
        }
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) {
            val cfg = config
                ?: throw IllegalStateException("LlamaCppInferenceEngine not initialised.")
            val handle = contextHandle
            if (handle == -1L) throw IllegalStateException("Engine not initialized.")
            try {
                LlamaCppBridge.nativeGenerate(
                    contextHandle = handle,
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
        initMutex.tryLock() // avoid race during release
        val h = contextHandle
        if (h != -1L) {
            LlamaCppBridge.nativeRelease(h)
            contextHandle = -1L
        }
        modelPfd?.close()
        modelPfd = null
        config = null
        activeModelName = null
        _activeModelNameFlow.value = null
        setReady(false)
        initMutex.unlock()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            DebugLogger.log("LLAMACPP", "System pressure (level=$level) — releasing engine memory")
            release()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {
        DebugLogger.log("LLAMACPP", "CRITICAL LOW MEMORY — emergency release")
        release()
    }
}
