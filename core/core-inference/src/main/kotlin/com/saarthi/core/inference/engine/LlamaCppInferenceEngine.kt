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
    }

    private val enginePrefs: SharedPreferences by lazy {
        context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
    }

    private fun vulkanPreviouslyCrashed(): Boolean =
        enginePrefs.getBoolean("vulkan_failed", false)

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
            val file = File(config.modelPath)
            if (!file.exists()) throw IllegalArgumentException("Model file not found: ${config.modelPath}")

            val fileSizeMb = file.length() / 1_048_576

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

            val estimatedRamAfterLoadMb = profile.availableRamMb - fileSizeMb
            val adaptiveMaxCtx = when {
                estimatedRamAfterLoadMb >= 3000L -> config.nCtx
                estimatedRamAfterLoadMb >= 1800L -> minOf(config.nCtx, 2048)
                estimatedRamAfterLoadMb >= 1000L -> minOf(config.nCtx, 1024)
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
            
            activeModelName = config.modelName
            _activeModelNameFlow.value = config.modelName
            
            setReady(true)
            DebugLogger.log("INIT", "Model ready  handle=$handle  nCtx=$usedCtx  $gpuMode")
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        if (!isReady) {
            DebugLogger.log("ENGINE", "Generate failed — engine not ready")
            close(IllegalStateException("LlamaCpp engine not initialised."))
            return@callbackFlow
        }

        val inferenceConfig = config ?: return@callbackFlow
        val handle = contextHandle

        // Prevent process kill during heavy prefill on budget devices
        if (!wakeLock.isHeld) wakeLock.acquire(5 * 60 * 1000L)

        DebugLogger.log("ENGINE", "Generate stream start (handle=$handle)")

        val job = launch(Dispatchers.IO) {
            try {
                LlamaCppBridge.nativeGenerate(handle, prompt) { token ->
                    if (isActive) {
                        trySend(token)
                    }
                }
                DebugLogger.log("ENGINE", "Native generation finished")
            } catch (e: Exception) {
                DebugLogger.log("ENGINE", "Native generation error: ${e.message}")
                close(e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                close()
            }
        }

        awaitClose {
            DebugLogger.log("ENGINE", "Closing stream flow")
            job.cancel()
        }
    }

    override suspend fun generate(prompt: String, packType: PackType): String = withContext(Dispatchers.IO) {
        val handle = contextHandle
        if (handle == -1L) throw IllegalStateException("LlamaCpp engine not initialised.")
        
        val sb = StringBuilder()
        LlamaCppBridge.nativeGenerate(handle, prompt) { token ->
            sb.append(token)
        }
        sb.toString()
    }

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        withContext(Dispatchers.IO) {
            if (!isReady) return@withContext
            DebugLogger.log("ENGINE", "Loading LoRA: ${adapterPath.substringAfterLast('/')} scale=$scale")
            try {
                LlamaCppBridge.nativeLoadLoraAdapter(contextHandle, adapterPath, scale)
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
