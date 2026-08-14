package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.model.PromptTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inference engine backed by Google AI Edge litertlm-android (same runtime as AI Edge Gallery).
 *
 * Uses the [Engine] + [Conversation] API:
 *   • [Engine] holds the model weights — loaded once, kept alive across requests.
 *   • [Conversation] holds the KV-cache — recreated cheaply per request.
 *
 * Backend hierarchy (auto-selected via [DeviceProfiler]):
 *   1. NPU  — Qualcomm QNN/Hexagon (SM8750 only, requires QNN-compiled .litertlm)
 *   2. GPU  — OpenCL/Vulkan (Adreno, Mali, Tensor GPU)
 *   3. CPU  — XNNPACK NEON (guaranteed path on all ARM64 devices)
 *
 * Key safety invariants preserved from the MediaPipe era:
 *   • Per-model crash tracking (crash count + GPU ban, 24h expiry), plus a
 *     per-SoC-family GPU ban set alongside it — a proven GPU/NPU fault on
 *     one model also distrusts GPU for every other model on this device's
 *     SoC family, not just the one that crashed. All of this persistence
 *     lives in [CrashRecoveryStore], extracted out of this class so it's a
 *     standalone, independently reviewable unit — this class only reacts
 *     to what that store reports, it doesn't touch SharedPreferences
 *     directly anywhere.
 *   • Generation heartbeat (10s interval) for crash timing diagnosis
 *   • FGS lifecycle via [InferenceService] during model load AND inference
 *   • Deferred engine close — never closes [Engine] while native thread is active
 *   • ComponentCallbacks2 memory pressure monitoring
 */
enum class CrashStage { MODEL_LOAD, GPU_INIT, CPU_INIT, CREATE_CONVERSATION, WARMUP, GENERATION, CLEANUP }

@Singleton
class LiteRTInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfiler: DeviceProfiler,
    private val crashRecoveryStore: CrashRecoveryStore,
    private val samplerPolicy: SamplerPolicy,
) : InferenceEngine, ComponentCallbacks2 {

    // ── Background lifecycle: debounced engine release ──────────────────────
    //
    // The scheduling/debounce/ActivityLifecycleCallbacks bookkeeping now
    // lives in [EngineLifecycleReleaseManager] (C3 God-class reduction) —
    // this class only supplies the callbacks that manager can't own itself:
    // its own mutable state (isNativeGenerating, initMutex) and the actual
    // native release calls, each hopped onto [engineDispatcher] before
    // running (the H13 fix folded into this extraction — these used to run
    // directly on the manager's Dispatchers.Main.immediate scope, meaning a
    // backgrounding event could block the main thread with native
    // Engine/Conversation close() calls and a synchronous SharedPreferences
    // commit()).
    private val engineLifecycleReleaseManager = EngineLifecycleReleaseManager(
        context = context,
        isNativeGenerating = { isNativeGenerating },
        isInitInProgress = { initMutex.isLocked },
        releaseConversationOnly = { withContext(engineDispatcher) { releaseConversationOnly() } },
        releaseEngine = { withContext(engineDispatcher) { release() } },
    )

    init {
        context.registerComponentCallbacks(this)
        engineLifecycleReleaseManager.register()
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null

    // When closeInternal() is called while a native generation is in progress,
    // we cannot close the Engine immediately. Save it here; the native 'done' /
    // 'error' callback closes it once the native thread finishes.
    @Volatile private var closingEngine: Engine? = null
    @Volatile private var closingConversation: Conversation? = null

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMaxTokens: Int = 0
    // The actual maxNumTokens passed to the Engine (≤ loadedMaxTokens which stores config value).
    @Volatile private var loadedEffectiveMaxTokens: Int = 0
    // The active model's catalog-provided sampler defaults (see ModelEntry.
    // defaultTemperature/topK) — captured at load time so samplerForActiveModel()
    // and activeModelDefaultTemperature can use them on later turns, once
    // InferenceConfig is no longer in scope. Defaults here are never read
    // before a successful initialize() sets them for real.
    @Volatile private var loadedTemperature: Float = 0.8f
    @Volatile private var loadedTopK: Int = 64

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
        private set

    private val _isInitializingFlow = MutableStateFlow(false)
    override val isInitializingFlow: Flow<Boolean> = _isInitializingFlow.asStateFlow()
    override val isInitializing: Boolean get() = _isInitializingFlow.value

    private val _activeModelNameFlow = MutableStateFlow<String?>(null)
    override val activeModelNameFlow: Flow<String?> = _activeModelNameFlow.asStateFlow()

    @Volatile override var activeModelName: String? = null
        private set

    // Surfaces the effective context-window the model was actually loaded
    // with (set alongside loadedEffectiveMaxTokens at the end of a successful
    // load, cleared to 0 on release). The prompt builder reads this to size
    // its char budget so it never overruns the input-token ceiling.
    override val maxContextTokens: Int get() = loadedEffectiveMaxTokens

    @Volatile private var usingNpu: Boolean = false
    @Volatile private var usingGpu: Boolean = false

    // True when the active Conversation has no turns in its KV cache yet.
    // Caller (ChatRepositoryImpl) reads this to decide whether to prepend the
    // system prompt to the next user message — same pattern as AI Edge Gallery.
    // Lifecycle:
    //   • init / resetSession      → true   (conversation just created)
    //   • after first onDone       → false  (KV cache now holds prior turns)
    //   • after onError + recycle  → true   (corrupted state, started over)
    @Volatile private var _isFreshConversation: Boolean = true
    override val isFreshConversation: Boolean get() = _isFreshConversation

    // isGenerating: tracks whether our *coroutine* is inside a generation block.
    // isNativeGenerating: tracks whether the litertlm native thread is still computing.
    // These are separate: cancelling the coroutine sets isGenerating=false immediately,
    // but the native thread keeps running until onDone/onError fires. Closing the Engine
    // while the native thread is active causes a use-after-free crash. closeInternal()
    // checks BOTH flags to decide whether to defer the close.
    @Volatile private var isGenerating: Boolean = false
    @Volatile override var isNativeGenerating: Boolean = false
        private set

    // Signals when the native inference thread has truly finished (onDone/onError fired).
    // The next generateStream() awaits this before creating a new Conversation.
    // LiteRT only supports ONE active Conversation at a time — we must never call
    // createConversation() while the previous native thread is still running.
    @Volatile private var nativeDoneSignal: CompletableDeferred<Unit>? = null

    // Set when the crash loop detector fires (≥3 consecutive crashes for this model).
    // generateStream() throws a user-visible error instead of crashing again.
    // Cleared at the start of every initialize() so a fresh model pick gets a clean slate.
    @Volatile private var crashLoopBlocked: Boolean = false

    private val initMutex = Mutex()
    private val generateMutex = Mutex()

    // Serializes EVERY Conversation create/close so two native sessions can never
    // coexist. litertlm allows exactly one Conversation per Engine; creating a
    // second one throws "FAILED_PRECONDITION: A session already exists". That race
    // happened because the per-turn recycle ran in a DETACHED coroutine (onDone/
    // onError) while the next turn's timeout-path force-recycle ALSO created one.
    // [recycleConversation] holds this lock and always CLOSES the current
    // conversation before creating the next, so the invariant "≤1 live
    // Conversation" is guaranteed regardless of how the paths interleave.
    private val conversationLock = Mutex()

    // Single-threaded dispatcher for all Engine calls. Ensures native objects are
    // never accessed concurrently and callbacks return to a stable thread context.
    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Point 4: onDone/onError used to each spin up a brand-new, untracked
    // `CoroutineScope(Dispatchers.IO)` — no SupervisorJob, no exception
    // handler — for the mandatory per-turn recycleConversation() call (see
    // conversationLock's kdoc above for why this must be async). Without a
    // handler, a failure inside recycleConversation() (e.g. the native
    // close()/create() calls it makes) was an uncaught exception that could
    // hard-crash the process on every single turn's completion, with no
    // trace. One shared, supervised, logged scope replaces both call sites.
    private val recycleExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        DebugLogger.log("LITERT", "Recycle failure: ${throwable::class.simpleName}: ${throwable.message}")
    }
    private val callbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + recycleExceptionHandler)

    // ── Version-based crash state reset ──────────────────────────────────────
    //
    // On each new APK install, clear all per-session crash tracking so stale crash
    // counts from a previous build don't trigger the crash loop blocker on first run.
    // GPU bans are also cleared — a new build may have different backend config.
    // The actual SharedPreferences work lives in CrashRecoveryStore; this class
    // only resolves the app version code (a Context/PackageManager concern, not
    // a crash-persistence one) and hands it off.
    init {
        val currentVersion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        }.getOrDefault(-1)
        crashRecoveryStore.resetOnVersionChange(currentVersion)
    }

    // ── Crash detection helpers ───────────────────────────────────────────────
    // All persisted crash-tracking state now lives in [CrashRecoveryStore] —
    // this class only calls it and reacts to what it reports. See that
    // class's kdoc for the full SharedPreferences key inventory.

    private fun setReady(value: Boolean) {
        isReady = value
        _isReadyFlow.value = value
    }

    private fun backendLabel() = when {
        usingNpu -> "NPU"
        usingGpu -> "GPU"
        else     -> "CPU"
    }

    // Returns true only when the model file has QNN-compiled layers for this SoC.
    // Generic .litertlm bundles (e.g. gemma-4-E2B-it.litertlm) contain no QNN layers
    // and will throw LiteRtLmJniException immediately if Backend.NPU is attempted.
    // SM8750: all catalog models have a QNN variant so NPU is always worth trying.
    // SM8550: only the sm8550-named bundle works on QNN; generic files fall to CPU.
    private fun isModelNpuOptimised(
        modelPath: String,
        profile: com.saarthi.core.inference.model.DeviceProfile,
    ): Boolean = when (profile.socFamily) {
        com.saarthi.core.inference.model.SocFamily.QUALCOMM_SM8750 -> true
        com.saarthi.core.inference.model.SocFamily.QUALCOMM_SM8550 ->
            modelPath.contains("sm8550", ignoreCase = true)
        else -> false
    }

    // ── Sampler config (model-aware) ──────────────────────────────────────────
    //
    // The actual decisions (temperature-override math, grounded-sampler
    // tuning, the RAG-marker string check) now live in [SamplerPolicy] —
    // extracted as part of the C3 God-class reduction. These three stay as
    // thin wrappers because they're called from many sites throughout this
    // class and only exist to supply the engine-local state (usingNpu,
    // loadedTemperature, loadedTopK) that SamplerPolicy itself doesn't own.
    //
    // temperature/topK are data-driven — see ModelEntry.defaultTemperature/
    // topK — captured at load time into loadedTemperature/loadedTopK below.
    override val activeModelDefaultTemperature: Float
        get() = loadedTemperature

    private fun samplerForActiveModel(): SamplerConfig? =
        samplerPolicy.samplerFor(usingNpu, loadedTemperature, loadedTopK)

    private fun groundedSamplerFor(): SamplerConfig? =
        samplerPolicy.groundedSamplerFor(usingNpu)

    /**
     * Tracks the sampler mode the live [activeConversation] was created
     * with so we know when to recycle on a mode flip (normal ↔ grounded).
     * Volatile because the per-turn onDone callback also touches it.
     */
    @Volatile private var conversationIsGrounded: Boolean = false

    // ── Streaming repetition guard ────────────────────────────────────────────
    //
    // Detects a DEGENERATE generation loop in the most recent streamed output and
    // returns true → caller calls conversation.cancelProcess() to stop the native
    // thread (litertlm's SamplerConfig has no repetition_penalty, so this is the
    // only lever). Whatever streamed up to that point stays visible.
    //
    // CRITICAL: tuned to fire ONLY on consecutive (back-to-back) repetition — the
    // true signature of a stuck model. The previous guard killed scattered
    // recurrence (any ≥8-char word 4× in 400 chars, or a 40-char phrase echoed
    // in the prior 200 chars), which false-positived on EVERY document answer,
    // because legal/grounded text legitimately repeats domain terms ("Data
    // Fiduciary", "personal data", "Data Principal") and list structure. That
    // truncated grounded replies at ~80–190 tokens. See [isRepetitionLoop].
    private fun detectRepetitionLoop(buf: StringBuilder): Boolean {
        if (buf.length < 60) return false
        // 360-char window so a longer repeating UNIT can still be caught: check #1
        // needs 3 copies in the window, so the period it can detect is window/3.
        // 240 only caught ≤80-char periods; the Kisan-pack 1B loop repeated an
        // ~88-char header line, which slipped through. 360 → periods up to ~120.
        return isRepetitionLoop(buf.substring(maxOf(0, buf.length - 360)))
    }

    // ── Initialize ────────────────────────────────────────────────────────────

    /**
     * Thin wrapper around [initializeInternal] that tracks [isInitializing] —
     * kept separate so the large existing init body below (crash recovery,
     * tier/GPU decisions, native load) stays untouched. Chat screens use
     * this to distinguish "still loading, please wait" from a genuine
     * failure when a message arrives before [isReady] flips true; ANY exit
     * (success or thrown) clears the flag via finally.
     */
    override suspend fun initialize(config: InferenceConfig) {
        _isInitializingFlow.value = true
        try {
            initializeInternal(config)
        } finally {
            _isInitializingFlow.value = false
        }
    }

    private suspend fun initializeInternal(config: InferenceConfig) = withContext(engineDispatcher) {
        initMutex.withLock {
            generateMutex.withLock {
                crashLoopBlocked = false

                // Crash loop breaker: must run before normal crash recovery.
                val loopBroken = crashRecoveryStore.breakCrashLoopIfNeeded(config.modelPath)
                if (loopBroken) {
                    crashLoopBlocked = true
                    crashRecoveryStore.markGenerationEnded()
                    crashRecoveryStore.markInitEnded()
                    closeInternal()
                    InferenceService.stop(context)
                    DebugLogger.log("LITERT", "Crash loop: throwing — model not compatible with this device")
                    throw RuntimeException(
                        "This model cannot run on your device.\n\n" +
                        "The AI engine crashed too many times while loading. " +
                        "Please choose a different model, or check for app updates."
                    )
                }

                val crashedDuringGen  = crashRecoveryStore.wasKilledDuringGeneration()
                val crashedDuringInit = crashRecoveryStore.wasKilledDuringInit()
                // ── JVM crash filter ──────────────────────────────────────
                // SaarthiApp's uncaught-exception handler stamps this flag
                // before the process dies. A JVM-side Throwable (NPE, OOM in
                // Kotlin code, anything Compose blows up on) is NOT a fault
                // of the inference engine — the engine had nothing to do with
                // it. Without this check, an unrelated JVM crash would land us
                // here, see a stale `wasUsingGpu=true` from a previous session's
                // successful generation, and ban the GPU for 24h on a perfectly
                // healthy device.
                val lastCrashWasJvm = crashRecoveryStore.lastCrashWasJvm()
                if (lastCrashWasJvm) {
                    val cls = crashRecoveryStore.lastCrashClass()
                    DebugLogger.log("LITERT", "[RECOVERY] Last crash was JVM ($cls) — engine not at fault. Skipping ban / count logic.")
                    crashRecoveryStore.clearJvmCrashState()
                }

                if ((crashedDuringGen || crashedDuringInit) && !lastCrashWasJvm) {
                    val wasGpuOrNpu = crashRecoveryStore.wasUsingGpuAtCrash()
                    val crashedModelPath = crashRecoveryStore.crashedModelPath()
                    val crashWasThisModel = (crashedDuringGen || crashedDuringInit) &&
                        (crashedModelPath == config.modelPath || crashedModelPath.isEmpty())
                    val batteryExempt = runCatching {
                        context.getSystemService(PowerManager::class.java)
                            .isIgnoringBatteryOptimizations(context.packageName)
                    }.getOrDefault(true)

                    val crashStage = crashRecoveryStore.crashStageRaw()
                    // Confirmed OS evidence, not just an educated guess from the
                    // pending-flag mechanism above — see the function's kdoc.
                    val confirmedLowMemory = crashRecoveryStore.lastExitWasConfirmedLowMemory()
                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  stage=$crashStage  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")
                    DebugLogger.log("LITERT", "  wasUsingGPU/NPU=$wasGpuOrNpu  crashedModel=${crashedModelPath.substringAfterLast('/')}")
                    DebugLogger.log("LITERT", "  currentModel=${config.modelPath.substringAfterLast('/')}  sameModel=$crashWasThisModel")
                    DebugLogger.log("LITERT", "  crashCount=${crashRecoveryStore.getCrashCount(config.modelPath)}  gpuBanned=${crashRecoveryStore.gpuPreviouslyCrashedDuringGen(config.modelPath)}")
                    DebugLogger.log("LITERT", "  batteryOptExempt=$batteryExempt  confirmedLowMemoryExit=$confirmedLowMemory")

                    // Only attribute GPU fault when a *generation* was actively
                    // running on the GPU. wasUsingGpuAtCrash() can be stale from
                    // a prior session's successful gen — gating on
                    // crashedDuringGen here prevents that misattribution.
                    val gpuActuallyAtFault = crashedDuringGen && wasGpuOrNpu && crashWasThisModel

                    // confirmedLowMemory only changes the WORDING here (a
                    // stronger diagnosis for the debug log), not which branch
                    // fires or any count/ban decision below — deliberately not
                    // wiring a new policy around it yet. This crash-recovery
                    // system has been tuned through several real field
                    // incidents (see the comments throughout this block); a
                    // single confirmed-LOW_MEMORY reading is real signal but
                    // not yet validated evidence for a NEW threshold, the same
                    // caution already applied to the GPU-margin constants
                    // elsewhere in this class.
                    val likelyCause = classifyLikelyCrashCause(
                        gpuActuallyAtFault = gpuActuallyAtFault,
                        crashedDuringGen = crashedDuringGen,
                        crashWasThisModel = crashWasThisModel,
                        crashedDuringInit = crashedDuringInit,
                        confirmedLowMemory = confirmedLowMemory,
                    )
                    DebugLogger.log("LITERT", "  likelyCause=$likelyCause")
                    DebugLogger.log("LITERT", "=== END CRASH RECOVERY ===")

                    if (crashWasThisModel) {
                        crashRecoveryStore.incrementCrashCount(config.modelPath, gpuActuallyAtFault)
                        if (gpuActuallyAtFault) {
                            val convWasReady = crashRecoveryStore.wasConvReadyAtCrash()
                            if (shouldBanGpuAfterAttributedGenCrash(convWasReady)) {
                                // Crash happened inside sendMessageAsync — GPU actually ran but died
                                // during token generation. Ban GPU for 24h, fall back to CPU.
                                crashRecoveryStore.markGpuGenCrashed(config.modelPath)
                                // Also ban GPU for the rest of this device's SoC family, not
                                // just this model file — see gpuFamilyPreviouslyCrashedDuringGen's
                                // kdoc. A fresh profile() call here is deliberate (the device
                                // hasn't been re-profiled yet at this point in initializeInternal)
                                // and cheap — this only runs on the rare crash-attribution path.
                                val crashSocFamily = deviceProfiler.profile().socFamily
                                crashRecoveryStore.markGpuGenCrashedForSoc(crashSocFamily)
                                DebugLogger.log("LITERT", "[CRASH] GPU/NPU crashed post-conv-ready (sendMessageAsync) — banning GPU for ${crashRecoveryStore.modelKey(config.modelPath)} AND for SoC family $crashSocFamily")
                            } else {
                                // Crash happened inside createConversation() — GPU never ran a single
                                // token. This is a shader compilation / KV-cache alloc timeout on
                                // first run. cacheDir means second run re-uses compiled shaders and
                                // typically completes 3-5× faster. Do NOT ban GPU.
                                DebugLogger.log("LITERT", "[CRASH] GPU/NPU crashed in createConversation() — NOT banning GPU, cached shaders may fix it next run")
                            }
                        }
                    }
                    // A DIFFERENT model crashed during init (typically a large
                    // model was OOM-killed and we are now loading a smaller
                    // fallback). Attribute the crash to the model that ACTUALLY
                    // crashed — NEVER to the model we are now loading. Previously
                    // this incremented config.modelPath, which let a heavy model's
                    // repeated OOM (e.g. Gemma 4 E4B) push the lightweight fallback
                    // (Compact 1B) to the UNSTABLE threshold and brick it — exactly
                    // backwards, since Compact is the safe model we fall back TO.
                    if (crashedDuringInit && !crashWasThisModel && crashedModelPath.isNotEmpty()) {
                        crashRecoveryStore.incrementCrashCount(crashedModelPath, false)
                    }
                    crashRecoveryStore.markGenerationEnded()
                    crashRecoveryStore.markInitEnded()
                    closeInternal()
                }

                // Already loaded with same config — skip reload.
                if (!crashedDuringGen && !crashedDuringInit &&
                    isReady &&
                    loadedModelPath == config.modelPath &&
                    loadedMaxTokens == config.maxTokens) {
                    DebugLogger.log("LITERT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    return@withLock
                }

                // Start FGS BEFORE any heavy work. Model loading allocates 500MB–4GB
                // and burns 100% CPU for 4–10s. Without FGS, Samsung OneUI 7 (Android 16)
                // kills the process before loading completes.
                InferenceService.startLoading(context)

                setReady(false)
                activeModelName = null
                _activeModelNameFlow.value = null
                closeInternal()

                if (config.modelPath.startsWith("/proc/self/fd/")) {
                    throw IllegalArgumentException(
                        "LiteRT models must be in the app's models folder with a real file path.\n\n" +
                        "Please download the model using the catalog instead of picking it from the file browser."
                    )
                }

                val file = File(config.modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found: ${config.modelPath}")
                if (file.length() <= 1024) {
                    file.delete()
                    throw IllegalArgumentException("Model file is corrupted or incomplete. Please delete and download it again.")
                }

                val sizeMb = file.length() / 1_048_576
                logDeviceState("INIT")
                val profile = deviceProfiler.profile()

                // mmap-aware: LiteRT demand-pages weights — gate on ~60% resident
                // estimate (same as token ladder / GPU margin), not 70% of full
                // file size (that falsely rejected capable devices).
                if (isInsufficientRamForModelLoad(profile.availableRamMb, sizeMb)) {
                    throw RuntimeException("Not enough active memory to run this model safely. Please close other apps or use a smaller model.")
                }

                val gpuBanned = crashRecoveryStore.gpuPreviouslyCrashedDuringGen(config.modelPath) ||
                    crashRecoveryStore.gpuFamilyPreviouslyCrashedDuringGen(profile.socFamily)

                // maxNumTokens = total context window (input + output tokens).
                // 1024 = Google AI Edge Gallery default for all backends including CPU.
                // Reduced to 512 on SM8550 (Snapdragon 8 Gen 2): createConversation() allocates
                // the full KV-cache synchronously. At 1024 tokens the allocation + shader warm-up
                // exceeds the ~5–7s Android process watchdog threshold, causing a SIGKILL before
                // a single token is generated. 512 halves the KV-cache, giving more headroom.
                val cpuCrashCount = crashRecoveryStore.getCpuCrashCount(config.modelPath)
                if (cpuCrashCount >= 3) {
                    DebugLogger.log("LITERT", "[CRASH] Model marked UNSTABLE after $cpuCrashCount CPU crashes.")
                    throw RuntimeException("Model is unstable on this device. Please use a smaller model.")
                }

                // Tier-aware KV-cache budget. We deliberately do NOT shrink it for
                // low battery — battery management is the OS's job; an offline AI
                // assistant has no business deciding the user's downloaded model
                // can't load because their phone is at 14 %. (The earlier
                // BATTERY_SAFE_MODE forced maxTokens=256 + 1 thread + GPU ban,
                // which made Gemma 4 fail to load with INTERNAL on every device
                // below 20 % battery — see v1.0.20.)
                // mmap-aware resident estimate. LiteRT demand-pages weights
                // from flash, so loading does NOT consume the full file size
                // in RAM — field logs on SM8550/11GB: loading E4B (3490MB)
                // moved availRam 4401→2712MB (~48% resident incl. GPU
                // buffers); E2B (2468MB) ~55-58%. 0.6 sits above every
                // observed resident fraction, so it stays conservative.
                // Shared by the token ladder AND the GPU memory gate below —
                // both previously charged the FULL file size and crippled E4B
                // (1536-token floor + forced CPU backend on fresh installs).
                // Pre-load reject gate above uses the same helper.
                val residentEstimateMb = estimateResidentModelMb(sizeMb)

                // Hoisted out of the token-ladder `run{}` below so the
                // LOW/MINIMAL-tier GPU-admission gate further down can reuse
                // the same tier classification instead of a second,
                // possibly drifting copy. Data-driven via config.promptTier
                // (see ModelEntry.promptTier) rather than matching the
                // model's name/path against substrings like "1b"/"compact"
                // or "gemma 4"/"gemma4" — a new catalog entry whose name
                // didn't happen to match one of those patterns used to
                // silently fall through to STANDARD's defaults regardless
                // of what the model actually was. The sizeMb fallback only
                // matters for a model that doesn't match any catalog entry
                // (config.promptTier stays PromptTier.STANDARD in that
                // case) — same safety net the old name-matching had for an
                // unrecognized model.
                val isLargeTier = isLargeTier(config.promptTier, sizeMb)
                val isCompactTier = isCompactTier(config.promptTier, sizeMb)

                // headroom from the resident estimate, not the file size — the
                // old (avail − FULL size) formula over-charged E4B by ~1.4GB,
                // so the "Best Quality" model almost always landed on the 1536
                // floor. See calculateEffectiveMaxTokens's kdoc for the full
                // decision ladder and the field incidents behind each branch.
                val headroomMb = profile.availableRamMb - residentEstimateMb
                val effectiveMaxTokens: Int = calculateEffectiveMaxTokens(
                    cpuCrashCount = cpuCrashCount,
                    isLargeTier = isLargeTier,
                    isCompactTier = isCompactTier,
                    configMaxTokens = config.maxTokens,
                    headroomMb = headroomMb,
                    sizeMb = sizeMb,
                    residentEstimateMb = residentEstimateMb,
                )

                // Honour the threads the device profiler recommended (typically
                // cpuCores − 2, clamped to 2..4 on Snapdragon 8 Gen 2 → 4). The
                // previous behaviour hardcoded 2 here regardless of the
                // InferenceConfig and silently capped XNNPACK's parallelism at
                // half the SoC's ARMv9 cores — a measurable tok/s loss on
                // flagship devices. After a CPU crash we still drop to 1 since
                // that's the only safe recovery strategy.
                val requestedThreads = config.nThreads.takeIf { it > 0 }
                    ?: profile.recommendedThreads
                val dynamicThreads = when {
                    cpuCrashCount >= 1 -> 1
                    else               -> requestedThreads.coerceIn(1, 4)
                }

                val xnnpackBanned = cpuCrashCount >= 2

                // GPU VRAM safety for large models — mmap-aware, like the
                // token ladder above. The old gate compared the FULL file size
                // against availRam × tier-multiplier, which banned E4B from
                // GPU whenever avail < ~4.1GB — on a fresh install (avail
                // ~3.9GB) E4B silently ran on CPU: 15-26s TTFT, 1.5-4.6 tps,
                // the reported "E4B chat not production ready". GPU load
                // actually consumes ~residentEstimate (observed ~1.6-1.8GB for
                // E4B on SM8550), so gate on resident + a size-aware safety
                // margin for KV-cache/activations/OS instead. On the same
                // fresh-install snapshot the new gate allows GPU (3866 ≥
                // 2094+1200) → ~4s TTFT. Mid/low-RAM devices keep bigger
                // margins — see gpuSafetyMarginMb() in the companion object
                // above for how "bigger" now scales continuously instead of
                // jumping at tier boundaries. A wrong call here degrades
                // safely: a GPU OOM crash is caught by the crash-recovery
                // ban and the next load goes CPU.
                val gpuSafetyMarginMb = gpuSafetyMarginMb(profile.totalRamMb)
                val memoryPressureBannedGpu =
                    profile.availableRamMb < residentEstimateMb + gpuSafetyMarginMb
                if (memoryPressureBannedGpu && sizeMb > 2000) {
                    DebugLogger.log("LITERT", "[GPU] BANNED by RAM pressure: availRam (${profile.availableRamMb}MB) < residentEst (${residentEstimateMb}MB) + margin (${gpuSafetyMarginMb}MB) on tier=${profile.tier}. Falling back to CPU.")
                }

                // See isGpuRestrictedToCompactOnLowTier() in the companion
                // object above for the full rationale (LOW/MINIMAL-tier GPU
                // is compact-model-only, and isLowRamDevice is an additional
                // conservative input, never a standalone veto).
                val gpuRestrictedToCompactOnLowTier = isGpuRestrictedToCompactOnLowTier(
                    tier = profile.tier,
                    isLowRamDevice = profile.isLowRamDevice,
                    isCompactModel = isCompactTier,
                )
                if (gpuRestrictedToCompactOnLowTier) {
                    DebugLogger.log("LITERT", "[GPU] Restricted on tier=${profile.tier} (isLowRamDevice=${profile.isLowRamDevice}): GPU is compact-model-only here, and ${crashRecoveryStore.modelKey(config.modelPath)} isn't compact. Falling back to CPU.")
                }

                // Pre-load memory trim — large models (>1.5 GB) benefit from
                // a GC pass before the native engine claims its working set.
                // Often reclaims 200-500 MB on Android and is the difference
                // between a clean GPU load and the gate above flipping to CPU.
                // No-op when memory is already plentiful; cheap (~200 ms).
                if (sizeMb > 1500) {
                    System.gc()
                    Thread.sleep(80)
                    System.gc()
                    Thread.sleep(80)
                    runCatching {
                        val am = context.getSystemService(android.app.ActivityManager::class.java)
                        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                        val freshAvailMb = mi.availMem / 1_048_576
                        DebugLogger.log("LITERT", "[MEM] pre-load trim: availRam ${profile.availableRamMb}MB → ${freshAvailMb}MB")
                    }
                }

                DebugLogger.log("LITERT", "Loading ${config.modelPath.substringAfterLast('/')}  size=${sizeMb}MB  maxTokens=$effectiveMaxTokens")

                crashRecoveryStore.markInitStarted(config.modelPath)
                crashRecoveryStore.markStage(CrashStage.MODEL_LOAD)
                try {
                    val newEngine = tryLoadWithFallback(
                        modelPath = config.modelPath,
                        maxTokens = effectiveMaxTokens,
                        profile = profile,
                        gpuBanned = gpuBanned || memoryPressureBannedGpu || gpuRestrictedToCompactOnLowTier,
                        dynamicThreads = dynamicThreads,
                        xnnpackBanned = xnnpackBanned,
                    )
                    
                    // CRITICAL: Save the active backend state BEFORE createConversation.
                    // A SIGKILL during createConversation is attributed via litert_was_using_gpu,
                    // but GPU is ONLY banned when litert_conv_ready was true (post-conv /
                    // sendMessageAsync crash) — see shouldBanGpuAfterAttributedGenCrash.
                    crashRecoveryStore.recordBackendForCrashAttribution(usingGpu || usingNpu)

                    // Undo lazy init per research: create conversation synchronously during init
                    DebugLogger.log("LITERT", "[INIT] Engine loaded. Creating conversation matrix synchronously...")
                    val samplerConfig = samplerPolicy.samplerFor(usingNpu, config.temperature, config.topK)
                    crashRecoveryStore.markStage(CrashStage.CREATE_CONVERSATION)
                    DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] createConversation (tokens=$effectiveMaxTokens, threads=$dynamicThreads, backend=${backendLabel()})")
                    try {
                        activeConversation = createConversationTracked(newEngine, samplerConfig)
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_EXIT] createConversation SUCCESS")
                    } catch (e: Exception) {
                        DebugLogger.log("LITERT", "[JNI_ERROR] createConversation threw: ${e.message}")
                        // newEngine already holds a fully-loaded native model (mmap'd weights) at
                        // this point — createConversation failing here must not leak it, since
                        // `engine` is only assigned AFTER this block succeeds (line ~929).
                        runCatching { newEngine.close() }
                            .onFailure { Timber.w(it, "LiteRT newEngine close-on-failure warning") }
                        throw e
                    } catch (t: Throwable) {
                        DebugLogger.log("LITERT", "[JNI_FATAL] createConversation threw Throwable: ${t.javaClass.simpleName}")
                        runCatching { newEngine.close() }
                            .onFailure { Timber.w(it, "LiteRT newEngine close-on-failure warning") }
                        throw t
                    }
                    
                    // NO warmup — matches Google AI Edge Gallery reference implementation.
                    // Fire-and-forget warmup corrupts conversation state when the user's
                    // first message arrives before the warmup completes, causing onDone
                    // to never fire and permanently blocking subsequent generations.
                    DebugLogger.log("LITERT", "[INIT] Conversation ready (no warmup — Gallery pattern).")
                    
                    engine = newEngine
                    
                    loadedModelPath = config.modelPath
                    loadedMaxTokens = config.maxTokens
                    loadedEffectiveMaxTokens = effectiveMaxTokens
                    loadedTemperature = config.temperature
                    loadedTopK = config.topK
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    _isFreshConversation = true   // brand-new Conversation, no turns in KV
                    crashRecoveryStore.markInitEnded()
                    // Point 4: do NOT clear GPU bans or crash counts here.
                    // Init (often on CPU while a ban is active) ≠ proof that
                    // GPU generation is safe. Clearing caused: gen crash →
                    // ban → CPU init succeeds → clear ban → retry GPU →
                    // crash again. Heal only via onDone when
                    // shouldClearGpuBanAfterSuccessfulGeneration is true, or
                    // CrashRecoveryStore's 24h expiry. Policy pinned by
                    // shouldClearStaleGpuRecoveryOnInitSuccess() === false.
                    setReady(true)
                    DebugLogger.log("LITERT", "Model ready & pre-warmed  $profile  backend=${backendLabel()}")
                } catch (e: OutOfMemoryError) {
                    crashRecoveryStore.markInitEnded()
                    val rawMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    val msg = "Not enough RAM to load this model. Close background apps and try again, or choose a smaller model."
                    DebugLogger.log("LITERT", "Load failed: $rawMsg")
                    Timber.e(e, "LiteRT model load failed")
                    InferenceService.stop(context)
                    throw RuntimeException("LiteRT failed to load model: $msg", e)
                } catch (e: Throwable) {
                    crashRecoveryStore.markInitEnded()
                    val rawMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    val msg = when {
                        rawMsg.contains("LiteRtLmJni", ignoreCase = true) ||
                        rawMsg.contains("JniException", ignoreCase = true) ->
                            "Your device could not load this model. " +
                            "This usually means the model requires hardware your phone does not support. " +
                            "Please try a different model."
                        else -> rawMsg
                    }
                    DebugLogger.log("LITERT", "Load failed: $rawMsg")
                    Timber.e(e, "LiteRT model load failed")
                    InferenceService.stop(context)
                    throw RuntimeException("LiteRT failed to load model: $msg", e)
                }
            }
        }
    }

    /**
     * Backend selection hierarchy:
     *   NPU  → GPU  → CPU
     *
     * NPU is fastest (Qualcomm QNN/Hexagon) but requires a device-specific .litertlm bundle.
     * GPU covers most flagship/mid-range via OpenCL. CPU is the guaranteed fallback.
     *
     * A prior GPU/NPU crash bans both GPU and NPU for 24h (they share the GPU_BAN flag)
     * and forces CPU, after which an OEM driver update restores GPU automatically.
     */
    private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
        dynamicThreads: Int,
        xnnpackBanned: Boolean,
    ): Engine {

        val modelNpuCompatible = isModelNpuOptimised(modelPath, profile)
        val npuEligible = isNpuEligible(profile.npuSafe, gpuBanned, modelNpuCompatible)
        // Memory fit is decided earlier (residentEstimate + gpuSafetyMargin →
        // folded into gpuBanned). isGpuEligible is SoC-safe + not-banned only.
        val gpuEligible = isGpuEligible(profile.gpuSafe, gpuBanned)

        // ── NPU (Qualcomm QNN — SM8750/SM8550 with device-specific .litertlm) ──
        // Only attempted when the SoC allows NPU AND the model file has QNN-compiled
        // layers for this SoC. Generic bundles have no QNN layers and throw immediately.
        if (npuEligible) {
            try {
                DebugLogger.log("LITERT", "[NPU] Trying QNN/Hexagon NPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.NPU(context.applicationInfo.nativeLibraryDir))
                    .also {
                        usingNpu = true
                        usingGpu = false
                        DebugLogger.log("LITERT", "[NPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[NPU] Failed: ${e.message?.take(120)}")
                Timber.w(e, "NPU backend failed")
            }
        } else if (profile.npuSafe && !modelNpuCompatible) {
            DebugLogger.log("LITERT", "[NPU] Skipped — model has no QNN layers for ${profile.socFamily}: ${modelPath.substringAfterLast('/')}")
        }

        // ── GPU (OpenCL/Vulkan — fast on Adreno, Mali, Tensor GPU) ────────────
        if (gpuEligible) {
            try {
                DebugLogger.log("LITERT", "[GPU] Trying OpenCL/Vulkan GPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.GPU())
                    .also {
                        usingNpu = false
                        usingGpu = true
                        DebugLogger.log("LITERT", "[GPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[GPU] Failed: ${e.message?.take(120)}")
                Timber.w(e, "GPU backend failed")
            }
        } else {
            val reason = when {
                // gpuBanned here is (crash ban || RAM-pressure gate ||
                // LOW/MINIMAL-tier compact-only restriction) merged by the
                // caller — the specific cause was already logged above.
                gpuBanned        -> "GPU banned for this load (crash ban, RAM pressure, or tier restriction — see earlier [GPU] line) for ${crashRecoveryStore.modelKey(modelPath)}"
                !profile.gpuSafe -> "gpuSafe=false — SoC=${profile.socModel} API=${profile.apiLevel}"
                // Memory budget is no longer a third isGpuEligible clause
                // (was a bytes-vs-tokens no-op); RAM gating is in gpuBanned.
                else             -> "gpuEligible=false"
            }
            DebugLogger.log("LITERT", "[GPU] Skipped — $reason")
        }

        // ── CPU (XNNPACK NEON — guaranteed path on all ARM64 devices) ──────────
        val backendLabel = if (xnnpackBanned) "PLAIN CPU (XNNPACK BANNED)" else "CPU/XNNPACK"
        DebugLogger.log("LITERT", "[CPU] Falling back to $backendLabel  threads=$dynamicThreads (auto-recovery)")
        return buildEngine(modelPath, maxTokens, Backend.CPU(dynamicThreads))
            .also {
                usingNpu = false
                usingGpu = false
            }
    }

    private fun buildEngine(modelPath: String, maxTokens: Int, backend: Backend): Engine {
        val engineConfig = EngineConfig(
            modelPath    = modelPath,
            backend      = backend,
            maxNumTokens = maxTokens,
            cacheDir     = if (backend !is Backend.CPU) context.codeCacheDir.absolutePath else null,
        )
        val e = Engine(engineConfig)
        try {
            e.initialize()  // blocking — must be called on background thread
        } catch (t: Throwable) {
            // initialize() can partially allocate native state before throwing — every
            // NPU→GPU→CPU fallback attempt in tryLoadWithFallback() calls this, so an
            // unclosed failure here leaks on every fallback step, not just once.
            runCatching { e.close() }
            throw t
        }
        return e
    }

    /**
     * Creates a LiteRT [Conversation] while tracking [CrashRecoveryStore]'s
     * `litert_conv_ready` flag: false for the duration of [Engine.createConversation],
     * true only after success. A SIGKILL mid-create leaves the flag false so the
     * next launch does NOT 24h-ban GPU (shader/KV timeout, not a generation fault).
     *
     * Every createConversation path MUST go through this helper — previously the
     * markConvStarted/markConvReady APIs existed but were never called, so every
     * crash defaulted to conv-ready=true and banned GPU incorrectly.
     */
    private fun createConversationTracked(eng: Engine, sampler: SamplerConfig?): Conversation {
        crashRecoveryStore.markConvStarted()
        val conversation = eng.createConversation(ConversationConfig(samplerConfig = sampler))
        crashRecoveryStore.markConvReady()
        return conversation
    }

    /**
     * Atomically replace [activeConversation]: close the current one (if any)
     * THEN create a fresh one — all under [conversationLock] so two native
     * sessions can never coexist (the "A session already exists" race).
     *
     * The current conversation reference is nulled BEFORE close so that even if
     * a concurrent recycle interleaves, neither path ever sees a live session
     * when it calls createConversation. Returns the new conversation, or null if
     * the engine is gone or creation failed (callers fall back to system state).
     */
    private suspend fun recycleConversation(sampler: SamplerConfig?): Conversation? =
        conversationLock.withLock {
            val eng = engine ?: run { activeConversation = null; return@withLock null }
            activeConversation?.let { old ->
                activeConversation = null
                runCatching { old.close() }
            }
            // Failure leaves litert_conv_ready=false (markConvStarted ran, markConvReady
            // did not) — correct for crash attribution if the process dies here.
            val fresh = runCatching { createConversationTracked(eng, sampler) }.getOrNull()
            activeConversation = fresh
            _isFreshConversation = true
            fresh
        }

    /**
     * [scheduleConversationRelease]'s stage-1 action: closes the active
     * Conversation and, unlike [recycleConversation], does NOT immediately
     * create a replacement — the whole point is to free the KV-cache
     * memory, not re-allocate it right away. Safe to leave
     * [activeConversation] null: generateStream()'s existing
     * "activeConversation == null" safety fallback (already exercised on
     * the very first turn after init) transparently calls
     * [recycleConversation] to create a fresh one on the next generation,
     * so no other code path needs to change for this.
     */
    private suspend fun releaseConversationOnly() {
        conversationLock.withLock {
            val old = activeConversation ?: return@withLock
            activeConversation = null
            runCatching { old.close() }
                .onFailure { Timber.w(it, "LiteRT Conversation-only release warning") }
        }
    }

    // ── generateStream ────────────────────────────────────────────────────────

    /**
     * Token-by-token streaming via [Conversation.sendMessageAsync].
     *
     * RECYCLED CONVERSATION (one per turn):
     *   • The first Conversation is created during [initialize] (so init-time
     *     shader warmup happens before the user sees a blank UI).
     *   • After [onDone] / [onError], the Conversation is closed and a fresh
     *     one is created immediately for the next turn. KV cache is therefore
     *     empty at the start of every sendMessageAsync.
     *   • Continuity across turns comes from a prompt-level recap built by
     *     [ChatRepositoryImpl.buildPriorTurnsRecap] — NOT from KV-cache reuse.
     *
     * Why we recycle (not stateful KV reuse):
     *   On Snapdragon 8 Gen 2 (SM8550) + Android 16, calling sendMessageAsync
     *   a second time on the same Conversation reliably SIGKILLs the process
     *   inside the litertlm GPU driver. Same failure pattern on the CPU
     *   backend. Recycling avoids the bug at the cost of a tiny per-turn
     *   createConversation() (~30–50ms with cached shaders).
     */
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow<String> {
        val eng = engine
            ?: throw IllegalStateException(
                if (crashLoopBlocked)
                    "This model cannot run on your device. Please go back and choose a different model."
                else
                    "LiteRT engine not initialised."
            )

        val timeoutMs = when {
            usingNpu -> 60_000L
            usingGpu -> 120_000L
            else     -> 300_000L
        }
        val genStartTimeMs = System.currentTimeMillis()
        DebugLogger.log("LITERT", "Stream start  backend=${backendLabel()}  timeout=${timeoutMs/1000}s  promptChars=${prompt.length}")
        logDeviceState("GEN")
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
            DebugLogger.log("LITERT", "[GEN] batteryOptExempt=$exempt  model=${loadedModelPath?.substringAfterLast('/') ?: "?"}")
        }.onFailure { DebugLogger.log("LITERT", "[GEN] batteryOptExempt=unavailable") }

        generateMutex.withLock {
            var tokenCount = 0
            var isFinished = false
            var watchdog: kotlinx.coroutines.Job? = null
            var heartbeat: kotlinx.coroutines.Job? = null

            try {
                isGenerating = true
                crashRecoveryStore.markGenerationStarted(usingGpu || usingNpu, loadedModelPath ?: "")

                // ── Wait for previous native thread (with timeout) ────────────
                nativeDoneSignal?.let { prev ->
                    if (!prev.isCompleted) {
                        DebugLogger.log("LITERT", "[GEN] Waiting for previous native thread to finish...")
                        // First try cancelProcess() to stop the native thread gracefully
                        runCatching<Unit?> { activeConversation?.cancelProcess() }
                        DebugLogger.log("LITERT", "[GEN] Called cancelProcess() — waiting for onError to fire...")
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) { prev.await() }
                            ?: run {
                                // cancelProcess didn't work — force cleanup as last resort.
                                // Serialized close-before-create so it can't collide with the
                                // detached recycle the stalled turn may still run.
                                DebugLogger.log("LITERT", "[GEN] TIMEOUT after cancelProcess — force recycling conversation")
                                isNativeGenerating = false
                                recycleConversation(samplerForActiveModel())
                                prev.complete(Unit)
                                DebugLogger.log("LITERT", "[GEN] Force-recycled conversation")
                            }
                    }
                }

                val thisDone = CompletableDeferred<Unit>()
                nativeDoneSignal = thisDone

                heartbeat = launch {
                    var tick = 0
                    while (!isFinished) {
                        delay(10_000L)
                        if (isFinished) break
                        tick++
                        val elapsedS = (System.currentTimeMillis() - genStartTimeMs) / 1000
                        DebugLogger.log("LITERT", "[HEARTBEAT $tick] elapsed=${elapsedS}s  tokens=$tokenCount  backend=${backendLabel()}")
                        logDeviceState("HB$tick")
                    }
                }

                // Pick sampler based on whether this turn is RAG-grounded
                // (prompt carries the strict-mode "ATTACHED EXCERPTS"
                // header). When mode flips from the previously-cached
                // conversation, recycle so the new sampler actually
                // takes effect — `createConversation` is the only place
                // the sampler is bound.
                val groundedNow = samplerPolicy.isGroundedPrompt(prompt)
                val desiredSampler = if (groundedNow) groundedSamplerFor()
                                     else samplerForActiveModel()
                if (activeConversation != null && conversationIsGrounded != groundedNow) {
                    DebugLogger.log("LITERT", "[SAMPLER] mode flipped (grounded=$groundedNow) — recycling conversation")
                    recycleConversation(desiredSampler)
                    conversationIsGrounded = groundedNow
                }
                // Safety fallback when there's no conversation yet (very
                // first send after init, or after a recycle failure).
                if (activeConversation == null) {
                    recycleConversation(desiredSampler)   // close-before-create handles a null active
                    conversationIsGrounded = groundedNow
                    DebugLogger.log("LITERT", "[GEN] Safety fallback: created new conversation  grounded=$groundedNow")
                }
                val conversation = activeConversation
                    ?: throw IllegalStateException("Could not create a conversation (engine released or out of memory).")

                watchdog = launch {
                    delay(timeoutMs)
                    if (!isFinished) {
                        DebugLogger.log("LITERT", "Watchdog: timeout at ${timeoutMs/1000}s — calling cancelProcess()")
                        // Use cancelProcess() to stop the native thread cleanly.
                        // onError(CancellationException) will handle recycling.
                        runCatching<Unit> { conversation.cancelProcess() }
                    }
                }

                DebugLogger.log("LITERT", "[GEN] Starting sendMessageAsync on persistent conversation...")

                isNativeGenerating = true

                // Streamed-output buffer used by the repetition guard below.
                // litertlm's SamplerConfig has no repetition_penalty / DRY parameter,
                // so on small models (Gemma 3 1B in particular) lists like "name all
                // states" can fall into a token loop where the same long word repeats.
                // We detect that on the streamed text and call cancelProcess() — what
                // was already emitted stays visible, the loop is cut short.
                val accumulated = StringBuilder()
                var repetitionStopFired = false
                // TTFT (time-to-first-token) ≈ prompt PREFILL cost + first decode
                // step — the dominant "why does it feel slow" number, and the one
                // that grows with prompt length every turn on the recycled-
                // conversation architecture. Isolated here so field logs show
                // prefill separately from decode throughput (tps), per device/
                // backend. −1 until the first token arrives.
                var ttftMs = -1L

                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val cleaned = message.toString().filterSpecialTokens()
                        if (cleaned.isNotEmpty()) {
                            if (ttftMs < 0L) {
                                ttftMs = System.currentTimeMillis() - genStartTimeMs
                                DebugLogger.log(
                                    "LITERT",
                                    "[GEN] first token — ttft=${ttftMs}ms (prefill+1st decode)  " +
                                        "promptChars=${prompt.length}  backend=${backendLabel()}",
                                )
                            }
                            accumulated.append(cleaned)
                            trySend(cleaned)
                            tokenCount++
                            if (!repetitionStopFired && detectRepetitionLoop(accumulated)) {
                                repetitionStopFired = true
                                DebugLogger.log(
                                    "LITERT",
                                    "[REP] Loop detected at $tokenCount tokens (chars=${accumulated.length}) — stopping native generation"
                                )
                                runCatching { conversation.cancelProcess() }
                            }
                        }
                    }

                    override fun onDone() {
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        heartbeat?.cancel()

                        // RECYCLE the Conversation after every turn.
                        //
                        // The stateful "reuse one Conversation across turns" pattern
                        // crashes natively on Snapdragon 8 Gen 2 (SM8550) + Android 16:
                        // the first sendMessageAsync succeeds, the second SIGKILLs the
                        // process inside the litertlm GPU driver. Same pattern reproduces
                        // on the CPU backend with watchdog kills. So we close the old
                        // Conversation and create a fresh one — context continuity comes
                        // from the prompt-level recap built by ChatRepositoryImpl, not
                        // from the KV cache.
                        //
                        // Async to avoid native deadlock — onDone runs on the native
                        // C++ inference thread, and createConversation() needs the same
                        // mutex that LiteRT holds until this callback returns.
                        // Preserve the sampler mode the just-completed turn used (a
                        // doc-Q&A follow-up is very likely also grounded).
                        callbackScope.launch {
                            val sc = if (conversationIsGrounded) groundedSamplerFor()
                                     else samplerForActiveModel()
                            recycleConversation(sc)
                            thisDone.complete(Unit)
                        }

                        crashRecoveryStore.resetCrashCount(loadedModelPath ?: "")
                        // Self-heal GPU bans only after a completed generation
                        // on GPU/NPU — the same class of work that earned the
                        // ban. CPU onDone must not clear a GPU ban.
                        if (shouldClearGpuBanAfterSuccessfulGeneration(usingGpu || usingNpu)) {
                            val path = loadedModelPath ?: ""
                            val socFamily = deviceProfiler.profile().socFamily
                            runCatching {
                                crashRecoveryStore.clearGpuGenCrashedFlag(path)
                                crashRecoveryStore.clearGpuGenCrashedFlagForSoc(socFamily)
                                DebugLogger.log(
                                    "LITERT",
                                    "[RECOVERY] GPU/NPU generation succeeded — clearing GPU ban for ${crashRecoveryStore.modelKey(path)} and SoC family $socFamily",
                                )
                            }
                        }
                        crashRecoveryStore.markGenerationEnded()
                        val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                        val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                        // Decode-only tps excludes the prefill wait, so it reflects
                        // the raw generation speed of the backend (GPU vs CPU) — the
                        // number to compare across devices once TTFT is separated out.
                        val decodeMs = if (ttftMs >= 0) elapsedMs - ttftMs else elapsedMs
                        val decodeTps = if (decodeMs > 0 && tokenCount > 1) (tokenCount - 1) * 1000f / decodeMs else tps
                        DebugLogger.log(
                            "LITERT",
                            "Stream done  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  ttft=${ttftMs}ms  " +
                                "tps=${"%.1f".format(tps)}  decodeTps=${"%.1f".format(decodeTps)}  " +
                                "backend=${backendLabel()}  conv=recycled",
                        )
                        // Drain BOTH deferred-close handles. Previously only
                        // closingEngine was closed here, so a closingConversation
                        // saved during a memory-pressure deferred close leaked until
                        // the next full close.
                        closingConversation?.let { old ->
                            runCatching { old.close() }
                            closingConversation = null
                        }
                        closingEngine?.let { old ->
                            runCatching { old.close() }
                            closingEngine = null
                        }
                        InferenceService.stop(context)
                        close()
                    }

                    override fun onError(error: Throwable) {
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        heartbeat?.cancel()

                        // Errors and cancellations leave the Conversation in a half-finished
                        // state — calling sendMessageAsync on it again throws FAILED_PRECONDITION.
                        // Recycle it so the next turn starts fresh. The next turn is treated as
                        // a brand-new conversation (system prompt re-sent).
                        // Async to avoid native-thread deadlock (LiteRT holds an internal mutex
                        // until this callback returns; createConversation() needs that mutex).
                        callbackScope.launch {
                            val sc = if (conversationIsGrounded) groundedSamplerFor()
                                     else samplerForActiveModel()
                            recycleConversation(sc)
                            thisDone.complete(Unit)
                        }

                        crashRecoveryStore.markGenerationEnded()

                        // cancelProcess() triggers CancellationException — treat as
                        // normal stop (user navigated away), not as an error.
                        if (error is java.util.concurrent.CancellationException || error is CancellationException) {
                            val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                            val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                            DebugLogger.log("LITERT", "Stream cancelled  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}")
                            crashRecoveryStore.resetCrashCount(loadedModelPath ?: "")
                            InferenceService.stop(context)
                            close()  // normal close — no error
                        } else {
                            DebugLogger.log("LITERT", "Generation error: ${error.message}")
                            InferenceService.stop(context)
                            close(RuntimeException("Generation failed: ${error.message}", error))
                        }
                    }
                })

                awaitClose {
                    watchdog?.cancel()
                    heartbeat?.cancel()
                    // CRITICAL: Stop the native thread immediately.
                    // Without this, the native thread runs indefinitely (model hasn't
                    // hit EOS) and blocks ALL future generations with FAILED_PRECONDITION.
                    // cancelProcess() triggers onError(CancellationException) which
                    // properly recycles the conversation.
                    // (Matches Gallery's stopResponse() → conversation.cancelProcess())
                    if (isNativeGenerating) {
                        DebugLogger.log("LITERT", "[GEN] Coroutine cancelled — calling cancelProcess() to stop native thread")
                        runCatching { conversation.cancelProcess() }
                    } else {
                        DebugLogger.log("LITERT", "[GEN] Coroutine cancelled — native already finished")
                    }
                }

            } catch (e: Throwable) {
                watchdog?.cancel()
                heartbeat?.cancel()
                if (e is CancellationException) throw e
                close(RuntimeException("Generation failed: ${e.message}", e))
            } finally {
                isGenerating = false
            }
        }
    }.flowOn(engineDispatcher)

    // ── Session reset ─────────────────────────────────────────────────────────

    /**
     * Discards the cached [activeConversation] so the next generation starts
     * with a clean KV cache. Called when the user creates a new chat, switches
     * sessions, or clears history.
     *
     * With per-request conversations in [generateStream], this is a safety net:
     * it ensures no stale conversation lingers if the architecture is ever
     * changed back to persistent sessions.
     */
    override suspend fun resetSession() = withContext(engineDispatcher) {
        generateMutex.withLock {
            if (engine == null) return@withLock
            // Serialized close-before-create (same invariant as the per-turn
            // recycle) so a reset can't collide with an in-flight recycle.
            recycleConversation(samplerForActiveModel())
            nativeDoneSignal = null
            _isFreshConversation = true   // next turn must re-send system prompt
            DebugLogger.log("LITERT", "[SESSION] Session reset — conversation recycled, KV cache cleared")
        }
    }

    /**
     * User-initiated cancel — drive the native cancelProcess() so the model
     * halts mid-stream when the user taps the Stop button in the UI. The
     * watchdog uses the same primitive on timeout; we just hoist it onto the
     * public interface.
     *
     * Safe to call from any thread, and a no-op when nothing is generating.
     * The onError callback wired in [generateStream] is what flips
     * `isNativeGenerating` back to false, so the FGS gets released through the
     * existing path without us having to duplicate cleanup here.
     */
    override fun cancelGeneration() {
        if (!isNativeGenerating) return
        DebugLogger.log("LITERT", "[CANCEL] User-initiated cancel — calling cancelProcess()")
        runCatching { activeConversation?.cancelProcess() }
            .onFailure { DebugLogger.log("LITERT", "[CANCEL] cancelProcess threw: ${it.message?.take(120)}") }
    }

    override fun release() {
        crashRecoveryStore.markStage(CrashStage.CLEANUP)
        closeInternal()
    }

    // ── Device state logging ──────────────────────────────────────────────────

    private fun logDeviceState(tag: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            DebugLogger.log("LITERT", "[$tag] RAM: avail=${mi.availMem/1_048_576}MB  total=${mi.totalMem/1_048_576}MB  lowMem=${mi.lowMemory}")
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(PowerManager::class.java)
                val thermalName = when (pm.currentThermalStatus) {
                    0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"
                    3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"
                    else -> "UNKNOWN(${pm.currentThermalStatus})"
                }
                DebugLogger.log("LITERT", "[$tag] Thermal: $thermalName")
            }
        } catch (_: Exception) {}
        try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            DebugLogger.log("LITERT", "[$tag] Battery: ${pct}%  charging=${bm.isCharging}")
        } catch (_: Exception) {}
    }

    // ── Engine lifecycle ──────────────────────────────────────────────────────

    private fun closeInternal() {
        if (isNativeGenerating) {
            // Native thread is still running — closing the Engine now would cause a
            // use-after-free crash. Save it for deferred close; the 'done/error'
            // callback will close it once the native thread exits.
            DebugLogger.log("LITERT", "Close deferred: native generation still in progress")
            if (engine != null) {
                closingEngine = engine
                closingConversation = activeConversation
                
                engine             = null
                activeConversation = null
                
                loadedModelPath = null
                loadedMaxTokens = 0
                activeModelName = null
                _activeModelNameFlow.value = null
                setReady(false)
            }
            return
        }
        
        runCatching { closingConversation?.close() }
        closingConversation = null
        runCatching { closingEngine?.close() }
            .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
        closingEngine = null

        if (engine == null) return
        setReady(false)
        
        runCatching { activeConversation?.close() }
        runCatching { engine?.close() }
            .onFailure { Timber.w(it, "LiteRT close warning") }
            
        engine             = null
        activeConversation = null
        
        loadedModelPath = null
        loadedMaxTokens = 0
        activeModelName = null
        _activeModelNameFlow.value = null
        DebugLogger.log("LITERT", "Engine & Conversation closed")
    }

    // ── Memory pressure callbacks ─────────────────────────────────────────────

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            DebugLogger.log("LITERT", "CRITICAL system pressure (level=$level) — releasing engine memory")
            release()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        DebugLogger.log("LITERT", "CRITICAL LOW MEMORY — emergency release")
        release()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun String.filterSpecialTokens() = this
        .replace("<start_of_turn>", "")
        .replace("<end_of_turn>", "")
        .replace("<eos>", "")
        .replace("<bos>", "")
}

private val REPETITION_WORD_SPLIT = Regex("[\\s\\p{Punct}]+")

/**
 * True when [tail] (the most recent ~240 streamed chars) shows a DEGENERATE,
 * back-to-back repetition loop — the signature of a stuck small model.
 *
 * Deliberately conservative: only CONSECUTIVE repetition triggers, so a coherent
 * answer that legitimately reuses a domain term ("Data Fiduciary", "personal
 * data") or list structure is NOT killed. The previous scattered-frequency guard
 * truncated nearly every document-grounded reply because legal/RAG text recurs
 * such terms naturally.
 *
 * Top-level `internal` so the detection is unit-testable without the engine.
 */
internal fun isRepetitionLoop(tail: String): Boolean {
    if (tail.length < 40) return false

    // 1. A phrase of length L that repeats 3× immediately back-to-back at the
    //    very end ("X. X. X." / "abcabcabc"). Prose never does this; a stuck
    //    model does. Per-token streaming means we hit a period boundary within a
    //    few tokens of a real loop starting. Cap raised 40→120 so a repeated
    //    sentence/header (the Kisan 1B loop was an ~88-char line) is caught, not
    //    just short word loops. Still 3× CONSECUTIVE identical — coherent answers
    //    never do that, so grounded/legal replies are unaffected.
    val maxL = minOf(120, tail.length / 3)
    for (l in maxL downTo 8) {
        val a = tail.substring(tail.length - l)
        val b = tail.substring(tail.length - 2 * l, tail.length - l)
        val c = tail.substring(tail.length - 3 * l, tail.length - 2 * l)
        if (a == b && b == c) return true
    }

    // 2. The same token repeated 5+ times back-to-back ("data data data data data").
    val words = tail.split(REPETITION_WORD_SPLIT).filter { it.isNotEmpty() }
    var run = 1
    for (i in 1 until words.size) {
        if (words[i].length >= 2 && words[i].equals(words[i - 1], ignoreCase = true)) {
            run++
            if (run >= 5) return true
        } else {
            run = 1
        }
    }
    return false
}

// ── GPU admission policy (pure, no Android/native deps) ──────────────────
// Top-level `internal`, same reason as [isRepetitionLoop] above: nothing
// here touches Context or the native engine, so it's unit-testable without
// Robolectric (which this project deliberately doesn't have).

/**
 * Combined OS + KV-cache/activations + GPU-backend-overhead reserve
 * required above a model's resident estimate before attempting GPU.
 *
 * Continuous in [totalRamMb], not a 3-step lookup — interpolated between
 * the same three field-validated anchors the old flat-tier version used
 * (1800MB at the LOW/MINIMAL floor, 1400MB at the LOW/MID boundary,
 * 1200MB at the MID/FLAGSHIP boundary; boundaries mirror DeviceTier's own
 * in DeviceProfile.kt rather than duplicating a second set of numbers). A
 * flat per-tier step gave a 6.1GB device and a 9.9GB device — both MID —
 * the identical margin; this scales smoothly across the range instead.
 * 1200MB is a floor for 10GB+, not decreased further past that point —
 * it's already validated as sufficient at ~11GB (see the fresh-install
 * evidence at this function's call site in [LiteRTInferenceEngine]), and
 * there's no evidence a 16GB device needs LESS margin than an 11GB one.
 *
 * The three anchor values themselves aren't re-derived here, only their
 * granularity is — this is still one combined reserve, not
 * independently-measured OS / KV-cache / backend-overhead terms.
 * Splitting it further needs real per-component data from testing across
 * the actual supported device matrix, not guessed constants.
 */
internal fun gpuSafetyMarginMb(totalRamMb: Long): Long {
    val lowAnchorRamMb = 3_500L   // DeviceTier LOW floor
    val midAnchorRamMb = 6_000L   // DeviceTier LOW/MID boundary
    val highAnchorRamMb = 10_000L // DeviceTier MID/FLAGSHIP boundary
    val lowMarginMb = 1_800L
    val midMarginMb = 1_400L
    val highMarginMb = 1_200L
    return when {
        totalRamMb <= lowAnchorRamMb -> lowMarginMb
        totalRamMb >= highAnchorRamMb -> highMarginMb
        totalRamMb <= midAnchorRamMb ->
            lerpMb(totalRamMb, lowAnchorRamMb, midAnchorRamMb, lowMarginMb, midMarginMb)
        else ->
            lerpMb(totalRamMb, midAnchorRamMb, highAnchorRamMb, midMarginMb, highMarginMb)
    }
}

internal fun lerpMb(x: Long, x0: Long, x1: Long, y0: Long, y1: Long): Long =
    y0 + (y1 - y0) * (x - x0) / (x1 - x0)

/**
 * Backend-eligibility checks — the "BackendSelector" half of the C3
 * backend/token-budget extraction (the other half is
 * [calculateEffectiveMaxTokens]). Extracted out of
 * [LiteRTInferenceEngine.tryLoadWithFallback]'s two `if` conditions,
 * unchanged, so they're directly unit-testable.
 *
 * Deliberately scoped to ELIGIBILITY only — whether a backend is even
 * worth attempting — not the sequential try-NPU-then-GPU-then-CPU
 * native-call orchestration in `tryLoadWithFallback`, which mutates live
 * engine state (`usingNpu`/`usingGpu`) and calls into the native `Engine`
 * constructor inside try/catch fallback chains. That orchestration isn't
 * safely separable from [LiteRTInferenceEngine] without either moving
 * ownership of that mutable native state into a new class — a
 * fundamentally bigger change than a function extraction, and one this
 * project has no real-device way to validate — or wrapping it in a
 * pass-through class that adds indirection without reducing real
 * coupling. Neither is attempted here.
 */
internal fun isNpuEligible(npuSafe: Boolean, gpuBanned: Boolean, modelNpuCompatible: Boolean): Boolean =
    npuSafe && !gpuBanned && modelNpuCompatible

/** [isNpuEligible]'s GPU counterpart — SoC/driver-safe and not banned.
 *
 * Deliberately two-part only. A previous third clause compared
 * `(safeModelBudgetMb * 1_048_576L) >= maxTokens` (bytes vs token count) —
 * unit-mismatched and always true for real budgets, so it gave a false
 * sense of memory gating. Actual GPU RAM admission is
 * `availableRam < residentEstimate + gpuSafetyMargin`, merged into
 * [gpuBanned] by the caller before this runs.
 */
internal fun isGpuEligible(gpuSafe: Boolean, gpuBanned: Boolean): Boolean =
    gpuSafe && !gpuBanned

/**
 * After a GPU/NPU crash was attributed to the current model (`gpuActuallyAtFault`),
 * whether to apply the 24h GPU ban (and SoC-family ban).
 *
 * [wasConvReadyAtCrash] mirrors [CrashRecoveryStore.wasConvReadyAtCrash]:
 *  - `true`  → crash after createConversation succeeded (typically mid-
 *    sendMessageAsync) → ban GPU.
 *  - `false` → crash while createConversation was in flight (shader compile /
 *    KV alloc timeout) → do NOT ban; cached shaders often fix the next run.
 *
 * Default when the pref was never written is `true` (conservative). That is
 * why every createConversation path must call markConvStarted before the
 * native call — otherwise createConversation timeouts were treated as
 * generation faults and banned GPU incorrectly.
 */
internal fun shouldBanGpuAfterAttributedGenCrash(wasConvReadyAtCrash: Boolean): Boolean =
    wasConvReadyAtCrash

/**
 * Whether successful model init alone may clear GPU bans / crash counts.
 *
 * Always `false`. Init proves load + createConversation, not that GPU
 * generation is safe. Clearing recovery state here let CPU-fallback inits
 * wipe a ban earned mid-sendMessageAsync and re-open the GPU crash loop.
 * Heal via [shouldClearGpuBanAfterSuccessfulGeneration] or 24h expiry.
 */
internal fun shouldClearStaleGpuRecoveryOnInitSuccess(): Boolean = false

/**
 * Whether a completed generation (`onDone`) may clear per-model and
 * SoC-family GPU bans.
 *
 * Only when that turn ran on GPU/NPU — the failing stage that set the ban.
 * A successful CPU turn must leave the ban in place until GPU/NPU proves
 * itself or [CrashRecoveryStore]'s 24h expiry elapses.
 */
internal fun shouldClearGpuBanAfterSuccessfulGeneration(wasUsingGpuOrNpu: Boolean): Boolean =
    wasUsingGpuOrNpu

/**
 * mmap-aware resident weight estimate (MB): ~60% of on-disk model size.
 * Field-validated above observed ~48–58% including GPU buffers; used by the
 * pre-load RAM gate, token ladder headroom, and GPU memory-pressure ban so
 * all three share one definition (not a second 70%-of-file-size gate).
 *
 * Public so feature-onboarding can mirror the engine load gate in the picker
 * (same threshold before download as at initialize).
 */
fun estimateResidentModelMb(sizeMb: Long): Long = (sizeMb * 6) / 10

/**
 * True when available RAM is below the mmap resident estimate — the model
 * should not be offered/loaded right now. Callers may still succeed after
 * the user closes background apps; GPU admission applies an *additional*
 * safety margin on top of this estimate and is independent.
 *
 * Public so feature-onboarding can mirror the engine load gate in the picker.
 */
fun isInsufficientRamForModelLoad(availableRamMb: Long, sizeMb: Long): Boolean =
    availableRamMb < estimateResidentModelMb(sizeMb)

/**
 * The token-ladder decision: how many tokens (input + output) to load the
 * model with. Extracted out of [LiteRTInferenceEngine.initializeInternal]'s
 * inline `run{}` block so this decision math is directly unit-testable —
 * that function itself needs the native engine and has no coverage.
 *
 * This is real, field-tuned logic, not a guess — every branch below traces
 * to a specific reported bug:
 *  • The CPU-crash recovery floors (never below 1536 for LARGE tier) fix a
 *    bricked-E4B incident: dropping to 256 tokens made every generation
 *    fail with "Input token ids are too long: 1092 >= 512", including a
 *    one-word "Hi".
 *  • The COMPACT-tier 2048 floor (was 512) fixes the Kisan pack failing on
 *    Compact with "Input token ids are too long: 1484 >= 512" — a Kisan RAG
 *    prompt runs ~1500 tokens.
 *  • The 4096 high-headroom scaling uses [residentEstimateMb] (mmap-aware,
 *    ~60% of file size), not the full file size — the old (avail − FULL
 *    size) formula over-charged E4B by ~1.4GB and almost always landed it
 *    on the 1536 floor, which also swaps in a leaner system prompt and
 *    starves recap + RAG (the reported "E4B worse than E2B" bug).
 *
 * Deliberately keeps its [DebugLogger] calls — this is the exact
 * field-diagnostic output this class's crash-recovery tooling depends on to
 * explain "why did this device get N tokens", so moving the decision logic
 * out must not silently change what shows up in a debug log a user shares
 * for support. The return value is what's unit-testable; the logging is
 * preserved byte-for-byte from before this extraction.
 */
internal fun calculateEffectiveMaxTokens(
    cpuCrashCount: Int,
    isLargeTier: Boolean,
    isCompactTier: Boolean,
    configMaxTokens: Int,
    headroomMb: Long,
    sizeMb: Long,
    residentEstimateMb: Long,
): Int {
    // KV-cache at 4096 scales with model size (~300MB for E2B, roughly 2×
    // for E4B) — the scaled-context gate must be stricter for files
    // ≥3000MB so E4B only gets 4096 with real room to spare.
    val scaled4096ThresholdMb = if (sizeMb >= 3000) 3400 else 2400
    return when {
        // Real CPU crash evidence — keep these as a recovery ladder since
        // they react to ACTUAL inference instability, not battery state.
        // Crash counters are cleared on every new APK install.
        cpuCrashCount >= 2 -> {
            val t = if (isLargeTier) 1536 else 64
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=$t (ULTRA-SAFE: CPU crash count $cpuCrashCount, largeTier=$isLargeTier)")
            t
        }
        cpuCrashCount >= 1 -> {
            val t = if (isLargeTier) 1536 else 256
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=$t (AUTO-RECOVERY: CPU crash count $cpuCrashCount, largeTier=$isLargeTier)")
            t
        }
        configMaxTokens > 0 && configMaxTokens <= 4096 -> {
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=$configMaxTokens (caller override)")
            configMaxTokens
        }
        isLargeTier && headroomMb >= scaled4096ThresholdMb -> {
            // High-end scaling: when the device has ample RAM headroom,
            // double the window so long multi-turn chats keep more history
            // before the recap has to drop turns. Mid-range stays at 2048
            // (next branch); low-RAM at 1536.
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=4096 (LARGE tier, high RAM headroom=${headroomMb}MB ≥ ${scaled4096ThresholdMb}MB — scaled context)  model=${sizeMb}MB  residentEst=${residentEstimateMb}MB")
            4096
        }
        isLargeTier && headroomMb >= 1500 -> {
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=2048 (LARGE tier — Gemma 4 needs room for system+recap+reply)  headroom=${headroomMb}MB  model=${sizeMb}MB  residentEst=${residentEstimateMb}MB")
            2048
        }
        isLargeTier -> {
            // Never drop a LARGE model below this — a Gemma 4 prompt
            // (system + RAG + recap) is ~1,100-1,450 tokens on its own.
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1536 (LARGE tier, low RAM headroom=${headroomMb}MB — must fit its own prompt)  residentEst=${residentEstimateMb}MB")
            1536
        }
        isCompactTier -> {
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=2048 (COMPACT tier — fits Kisan RAG prompt)")
            2048
        }
        headroomMb < 2048 -> {
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 — low RAM headroom=${headroomMb}MB")
            512
        }
        else -> {
            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1024 (STANDARD tier default)  headroom=${headroomMb}MB")
            1024
        }
    }
}

/**
 * Token-ladder/context-window "LARGE tier" classification — data-driven via
 * [promptTier] (see ModelEntry.promptTier), with a [sizeMb]-based fallback
 * that only applies when promptTier is STANDARD (a model that doesn't
 * match any current catalog entry) — the same safety net the old
 * name-matching logic had for an unrecognized model, so a large-but-
 * unclassified file still gets a large-enough context budget instead of
 * silently landing on STANDARD's smaller default.
 */
internal fun isLargeTier(promptTier: PromptTier, sizeMb: Long): Boolean =
    promptTier == PromptTier.LARGE || (promptTier == PromptTier.STANDARD && sizeMb > 1500)

/** [isLargeTier]'s counterpart for the COMPACT end of the ladder. */
internal fun isCompactTier(promptTier: PromptTier, sizeMb: Long): Boolean =
    promptTier == PromptTier.COMPACT || (promptTier == PromptTier.STANDARD && sizeMb < 700)

/**
 * LOW/MINIMAL-tier devices (roughly ≤6GB total RAM) treat GPU as an
 * optional path for the compact model only, not a default entitlement the
 * way MID/FLAGSHIP get it — even when the memory-pressure gate clears.
 * CPU stays the dependable default for the segment with the least
 * headroom and the most OEM driver variance; a larger model on these
 * devices only ever gets CPU.
 *
 * [isLowRamDevice] (Android's own OEM-influenced
 * ActivityManager.isLowRamDevice() classification) widens this same
 * restriction to any device the OS itself flags as memory-constrained,
 * even one whose totalRamMb happens to land in MID. It's an ADDITIONAL
 * conservative input, not a standalone veto: it only ever makes this
 * restriction apply to MORE devices, never bans GPU by itself independent
 * of tier/model, and never overrides the real-time memory-pressure gate
 * elsewhere.
 */
internal fun isGpuRestrictedToCompactOnLowTier(
    tier: com.saarthi.core.inference.model.DeviceTier,
    isLowRamDevice: Boolean,
    isCompactModel: Boolean,
): Boolean {
    val tierRestricted =
        tier == com.saarthi.core.inference.model.DeviceTier.LOW ||
            tier == com.saarthi.core.inference.model.DeviceTier.MINIMAL
    return (tierRestricted || isLowRamDevice) && !isCompactModel
}

/**
 * Picks the crash-recovery diagnosis string for the debug log. Order
 * matters: GPU/NPU fault takes priority over a same-model CPU crash, which
 * takes priority over an init crash, matching the priority the original
 * inline `when` block in [LiteRTInferenceEngine.initialize] used.
 * [confirmedLowMemory] only changes the wording (confirmed OS evidence vs.
 * an educated guess) — see that call site's comment for why it doesn't
 * change which branch fires or feed into any count/ban decision yet.
 */
internal fun classifyLikelyCrashCause(
    gpuActuallyAtFault: Boolean,
    crashedDuringGen: Boolean,
    crashWasThisModel: Boolean,
    crashedDuringInit: Boolean,
    confirmedLowMemory: Boolean,
): String = when {
    gpuActuallyAtFault ->
        "GPU/NPU_FAULT: inference caused SIGKILL on GPU/NPU backend — banning GPU for 24h."
    crashedDuringGen && crashWasThisModel && confirmedLowMemory ->
        "CPU_CRASH: Process killed during CPU generation — CONFIRMED low-memory kill (ApplicationExitInfo)."
    crashedDuringGen && crashWasThisModel ->
        "CPU_CRASH: Process killed during CPU generation. " +
            "Possible OEM watchdog, OOM, or native LiteRT issue."
    crashedDuringInit && confirmedLowMemory ->
        "INIT_CRASH: Killed during model load — CONFIRMED low-memory kill (ApplicationExitInfo). " +
            "Close background apps and retry, or choose a smaller model."
    crashedDuringInit ->
        "INIT_CRASH: Killed during model load — likely OOM. " +
            "Close background apps and retry, or choose a smaller model."
    else -> "UNKNOWN"
}
