package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 *   • Per-model crash tracking (crash count + GPU ban, 24h expiry)
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
    private val generationPreference: com.saarthi.core.inference.GenerationPreference,
) : InferenceEngine, ComponentCallbacks2 {

    init {
        context.registerComponentCallbacks(this)
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

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
        private set

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

    // Crash detection: synchronous SharedPrefs writes survive process kills.
    //   litert_gen_pending      — set during generation; true at startup → crashed mid-response
    //   litert_init_pending     — set during model init; true at startup → crashed mid-load (OOM)
    //   litert_was_using_gpu    — which backend was active at crash (GPU/NPU = true)
    //   litert_crash_model_path — which model was generating at crash
    //   litert_conv_ready       — false during createConversation(), true after; crash while false = don't ban GPU
    //   litert_crash_count_*    — per-model consecutive crash count
    //   litert_gpu_ban_*        — per-model GPU ban flag (true = use CPU for 24h)
    //   litert_gpu_ban_ts_*     — per-model GPU ban timestamp
    private val enginePrefs: SharedPreferences
        get() = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val GPU_BAN_EXPIRY_MS = 24 * 60 * 60 * 1000L          // 24 hours
        // Crash counts auto-expire after this window. Previously they only
        // reset on a successful onDone or a version bump (= reinstall),
        // which left users permanently locked out of a model that hit the
        // crash-loop threshold even once. Aligning with the GPU-ban window
        // means a model that crashed yesterday is usable again today
        // without uninstall+reinstall.
        private const val CRASH_COUNT_EXPIRY_MS = 24 * 60 * 60 * 1000L      // 24 hours
    }

    // ── Version-based crash state reset ──────────────────────────────────────

    // On each new APK install, clear all per-session crash tracking so stale crash
    // counts from a previous build don't trigger the crash loop blocker on first run.
    // GPU bans are also cleared — a new build may have different backend config.
    init {
        val prefs = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)
        val currentVersion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        }.getOrDefault(-1)
        val storedVersion = prefs.getInt("litert_app_version", 0)
        if (currentVersion != -1 && currentVersion != storedVersion) {
            val editor = prefs.edit()
            editor.putBoolean("litert_gen_pending", false)
            editor.putBoolean("litert_init_pending", false)
            editor.putBoolean("litert_conv_ready", true)
            prefs.all.keys.filter {
                it.startsWith("litert_crash_count_") ||
                it.startsWith("litert_cpu_crash_count_") ||  // separate prefix; was leaking across installs
                it.startsWith("litert_gpu_ban_")
            }.forEach { editor.remove(it) }
            editor.putInt("litert_app_version", currentVersion)
            editor.commit()
            DebugLogger.log("LITERT", "[VERSION] New install v$currentVersion (was v$storedVersion) — crash state cleared")
        }
    }

    // ── Crash detection helpers ───────────────────────────────────────────────

    private fun wasKilledDuringGeneration() =
        enginePrefs.getBoolean("litert_gen_pending", false)

    private fun wasKilledDuringInit() =
        enginePrefs.getBoolean("litert_init_pending", false)

    private fun modelKey(modelPath: String) = modelPath.substringAfterLast('/')

    /**
     * Returns the per-model consecutive-crash count, automatically expiring
     * it after [CRASH_COUNT_EXPIRY_MS] of no new crashes.
     *
     * Legacy state from older APK versions has no timestamp; we stamp it
     * on first read so the 24-hour clock starts then. After expiry the
     * count is wiped so the user gets a clean attempt without a reinstall.
     */
    private fun getCrashCount(modelPath: String): Int =
        readExpiringCount("litert_crash_count_${modelKey(modelPath)}")

    private fun getCpuCrashCount(modelPath: String): Int =
        readExpiringCount("litert_cpu_crash_count_${modelKey(modelPath)}")

    private fun readExpiringCount(baseKey: String): Int {
        val count = enginePrefs.getInt(baseKey, 0)
        if (count == 0) return 0
        val tsKey = "${baseKey}_ts"
        val ts = enginePrefs.getLong(tsKey, 0L)
        if (ts == 0L) {
            // Legacy state from before timestamp tracking — stamp it now
            // so the expiry clock starts from this read.
            enginePrefs.edit().putLong(tsKey, System.currentTimeMillis()).apply()
            return count
        }
        val ageMs = System.currentTimeMillis() - ts
        if (ageMs >= CRASH_COUNT_EXPIRY_MS) {
            DebugLogger.log(
                "LITERT",
                "$baseKey expired after ${ageMs / 3_600_000}h — clearing (auto-recovery)",
            )
            enginePrefs.edit().remove(baseKey).remove(tsKey).apply()
            return 0
        }
        return count
    }

    private fun incrementCrashCount(modelPath: String, wasGpuOrNpu: Boolean) {
        val key = modelKey(modelPath)
        val now = System.currentTimeMillis()
        val count = getCrashCount(modelPath) + 1
        val editor = enginePrefs.edit()
            .putInt("litert_crash_count_$key", count)
            .putLong("litert_crash_count_$key" + "_ts", now)

        if (!wasGpuOrNpu) {
            val cpuCount = getCpuCrashCount(modelPath) + 1
            editor.putInt("litert_cpu_crash_count_$key", cpuCount)
                .putLong("litert_cpu_crash_count_$key" + "_ts", now)
            DebugLogger.log("LITERT", "Crash count for $key: $count (CPU count: $cpuCount)")
        } else {
            DebugLogger.log("LITERT", "Crash count for $key: $count")
        }
        editor.commit()
    }

    private fun resetCrashCount(modelPath: String) {
        val key = modelKey(modelPath)
        enginePrefs.edit()
            .remove("litert_crash_count_$key")
            .remove("litert_crash_count_${key}_ts")
            .apply()
    }

    private fun gpuPreviouslyCrashedDuringGen(modelPath: String): Boolean {
        val key = modelKey(modelPath)
        if (!enginePrefs.getBoolean("litert_gpu_ban_$key", false)) return false
        val bannedAt = enginePrefs.getLong("litert_gpu_ban_ts_$key", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true
        } else {
            DebugLogger.log("LITERT", "GPU ban expired for $key after ${banAgeMs / 3_600_000}h — retrying GPU")
            clearGpuGenCrashedFlag(modelPath)
            false
        }
    }

    private fun breakCrashLoopIfNeeded(modelPath: String): Boolean {
        val count = getCrashCount(modelPath)
        if (count >= 4) {
            val key = modelKey(modelPath)
            DebugLogger.log(
                "LITERT",
                "CRASH LOOP ($count crashes for $key) — blocking. Auto-expires in ${CRASH_COUNT_EXPIRY_MS / 3_600_000}h, " +
                "or sooner if a generation eventually succeeds.",
            )
            // Reset the in-flight crash trackers (so we don't double-count next
            // attempt), but DO NOT reset the persistent count — that's how the
            // block stays in place until the 24-hour expiry kicks in via
            // getCrashCount() / readExpiringCount(). Reinstall is no longer
            // required as a recovery path.
            enginePrefs.edit()
                .putBoolean("litert_gen_pending", false)
                .putBoolean("litert_init_pending", false)
                .putBoolean("litert_gpu_ban_$key", false)
                .putLong("litert_gpu_ban_ts_$key", 0L)
                .commit()
            crashLoopBlocked = true
            return true
        }
        return false
    }

    private fun markGpuGenCrashed(modelPath: String) {
        val key = modelKey(modelPath)
        enginePrefs.edit()
            .putBoolean("litert_gpu_ban_$key", true)
            .putLong("litert_gpu_ban_ts_$key", System.currentTimeMillis())
            .commit()
    }

    private fun clearGpuGenCrashedFlag(modelPath: String) {
        val key = modelKey(modelPath)
        enginePrefs.edit()
            .putBoolean("litert_gpu_ban_$key", false)
            .putLong("litert_gpu_ban_ts_$key", 0L)
            .apply()
    }

    private fun markInitStarted(modelPath: String) {
        enginePrefs.edit()
            .putBoolean("litert_init_pending", true)
            .putString("litert_crash_model_path", modelPath)
            .commit()
    }

    private fun markInitEnded() =
        enginePrefs.edit().putBoolean("litert_init_pending", false).commit()

    private fun markGenerationStarted() {
        enginePrefs.edit()
            .putBoolean("litert_gen_pending", true)
            .putBoolean("litert_was_using_gpu", usingGpu || usingNpu)
            .putString("litert_crash_model_path", loadedModelPath ?: "")
            .commit()
    }

    private fun markGenerationEnded() =
        enginePrefs.edit().putBoolean("litert_gen_pending", false).commit()

    private fun wasUsingGpuAtCrash() =
        enginePrefs.getBoolean("litert_was_using_gpu", false)

    private fun markConvStarted() =
        enginePrefs.edit().putBoolean("litert_conv_ready", false).commit()

    private fun markConvReady() =
        enginePrefs.edit().putBoolean("litert_conv_ready", true).commit()

    // Default true = conservative (unknown crash assumed post-conv → ban GPU).
    // markConvStarted() sets false before createConversation(); markConvReady() sets true after.
    // A crash in createConversation() leaves the pref false → GPU is NOT banned on next run
    // (second run has cached shaders, may complete in time).
    private fun wasConvReadyAtCrash() =
        enginePrefs.getBoolean("litert_conv_ready", true)

    private fun markStage(stage: CrashStage) {
        enginePrefs.edit().putString("litert_crash_stage", stage.name).commit()
        DebugLogger.log("LITERT", "[STAGE] Entering stage: $stage")
    }

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
    // Google's recommended Gemma 3/4 sampling — temp=1.0, topK=64, topP=0.95 —
    // matches the AI Edge Gallery reference implementation. The previous
    // temp=0.7 + topK=40 combination caused 1B models to loop on high-probability
    // sequences (visible repetition in chat). Higher temp + larger topK gives
    // the diversity Gemma was trained for.
    //
    // NPU returns null because QNN handles sampling internally on Hexagon.
    private fun samplerFor(modelPath: String): SamplerConfig? {
        if (usingNpu) return null
        val name = modelPath.lowercase()
        val isLargeGemma = name.contains("gemma3") || name.contains("gemma-3") || name.contains("gemma 3") ||
            name.contains("gemma4") || name.contains("gemma-4") || name.contains("gemma 4")
        val topK = if (isLargeGemma) 64 else 40
        // User temperature override (Settings → Response style). A value >= 0
        // replaces the model's recommended default; AUTO (-1) defers to it,
        // so users who never touch the slider keep the prior behaviour.
        // topK/topP stay model-tuned — only the temperature is user-facing.
        val userTemp = generationPreference.temperature.value
        val temp = (if (userTemp >= 0f) userTemp else baseTemperatureFor(name)).toDouble()
        return SamplerConfig(topK = topK, topP = 0.95, temperature = temp)
    }

    /** Google's recommended temperature per Gemma family — the AUTO baseline. */
    private fun baseTemperatureFor(modelNameLower: String): Float = when {
        modelNameLower.contains("gemma3") || modelNameLower.contains("gemma-3") || modelNameLower.contains("gemma 3") -> 1.0f
        // Gemma 4 E4B ONLY: a tighter default than E2B gives the bigger model
        // crisper, more authoritative, instruction-following answers (at temp 1.0
        // it tends to ramble). E2B's quality is already good → it stays at 1.0.
        // Matches on the model FILE path (…gemma-4-E4B-it…); the display name has
        // no "e4b", so callers must pass the path (see samplerForActiveModel).
        (modelNameLower.contains("gemma4") || modelNameLower.contains("gemma-4") || modelNameLower.contains("gemma 4")) &&
            modelNameLower.contains("e4b") -> 0.7f
        modelNameLower.contains("gemma4") || modelNameLower.contains("gemma-4") || modelNameLower.contains("gemma 4") -> 1.0f
        else -> 0.8f
    }

    override val activeModelDefaultTemperature: Float
        // Prefer the file path: it carries the E2B/E4B variant ("…E4B-it…"); the
        // display name ("Gemma 4 · Best Quality") does not, so per-variant tuning
        // (e.g. E4B's tighter temperature) would be invisible from the name alone.
        get() = baseTemperatureFor((loadedModelPath ?: activeModelName ?: "").lowercase())

    private fun samplerForActiveModel(): SamplerConfig? =
        // File path first — see activeModelDefaultTemperature: needed so the
        // per-turn sampler can distinguish E4B from E2B (display name can't).
        samplerFor(loadedModelPath ?: activeModelName ?: "")

    /**
     * Tighter sampler for RAG-grounded turns. Used when the incoming
     * prompt carries the strict-mode "ATTACHED EXCERPTS" header so the
     * detection is automatic and no API change is needed.
     *
     * Why a separate sampler for RAG mode:
     *  • The default Gemma 3/4 sampler (temp=1.0, topK=64, topP=0.95) is
     *    optimised for creative chat — it tolerates high-probability
     *    diversion. Inside RAG mode that diversion turns into the
     *    repetition loops we kept seeing ("[REP] Loop detected at 82
     *    tokens" on the latest production log).
     *  • Industry standard for document-grounded Q&A is temp ≈ 0.3–0.5
     *    with tighter top-p — pulls the model toward verbatim quotation
     *    of the cited excerpt, which is exactly what we want when we've
     *    already told it "answer ONLY from these excerpts".
     *  • NPU still returns null (Hexagon does sampling on-chip).
     */
    private fun groundedSamplerFor(modelPath: String): SamplerConfig? {
        if (usingNpu) return null
        return SamplerConfig(topK = 40, topP = 0.85, temperature = 0.4)
    }

    /**
     * Marker baked into [ChatRepositoryImpl.buildRagPromptBlock] for the
     * LARGE/STANDARD strict-mode block. Detecting this here keeps the
     * engine API unchanged — no new parameter on `generateStream()` — and
     * automatically swaps in the grounded sampler whenever RAG context is
     * present. False-positive risk is negligible: the literal phrase
     * "ATTACHED EXCERPTS" doesn't appear in normal chat content.
     */
    private fun isGroundedPrompt(prompt: String): Boolean =
        prompt.contains("ATTACHED EXCERPTS")

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

    override suspend fun initialize(config: InferenceConfig) = withContext(engineDispatcher) {
        initMutex.withLock {
            generateMutex.withLock {
                crashLoopBlocked = false

                // Crash loop breaker: must run before normal crash recovery.
                val loopBroken = breakCrashLoopIfNeeded(config.modelPath)
                if (loopBroken) {
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                    InferenceService.stop(context)
                    DebugLogger.log("LITERT", "Crash loop: throwing — model not compatible with this device")
                    throw RuntimeException(
                        "This model cannot run on your device.\n\n" +
                        "The AI engine crashed too many times while loading. " +
                        "Please choose a different model, or check for app updates."
                    )
                }

                val crashedDuringGen  = wasKilledDuringGeneration()
                val crashedDuringInit = wasKilledDuringInit()
                // ── JVM crash filter ──────────────────────────────────────
                // SaarthiApp's uncaught-exception handler stamps this flag
                // before the process dies. A JVM-side Throwable (NPE, OOM in
                // Kotlin code, anything Compose blows up on) is NOT a fault
                // of the inference engine — the engine had nothing to do with
                // it. Without this check, an unrelated JVM crash would land us
                // here, see a stale `wasUsingGpu=true` from a previous session's
                // successful generation, and ban the GPU for 24h on a perfectly
                // healthy device.
                val lastCrashWasJvm = enginePrefs.getBoolean("saarthi_last_crash_was_jvm", false)
                if (lastCrashWasJvm) {
                    val cls = enginePrefs.getString("saarthi_last_crash_class", "?")
                    DebugLogger.log("LITERT", "[RECOVERY] Last crash was JVM ($cls) — engine not at fault. Skipping ban / count logic.")
                    enginePrefs.edit()
                        .remove("saarthi_last_crash_was_jvm")
                        .remove("saarthi_last_crash_class")
                        .putBoolean("litert_init_pending", false)
                        .putBoolean("litert_gen_pending", false)
                        .commit()
                }

                if ((crashedDuringGen || crashedDuringInit) && !lastCrashWasJvm) {
                    val wasGpuOrNpu = wasUsingGpuAtCrash()
                    val crashedModelPath = enginePrefs.getString("litert_crash_model_path", "") ?: ""
                    val crashWasThisModel = (crashedDuringGen || crashedDuringInit) &&
                        (crashedModelPath == config.modelPath || crashedModelPath.isEmpty())
                    val batteryExempt = runCatching {
                        context.getSystemService(PowerManager::class.java)
                            .isIgnoringBatteryOptimizations(context.packageName)
                    }.getOrDefault(true)

                    val crashStage = enginePrefs.getString("litert_crash_stage", "UNKNOWN")
                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  stage=$crashStage  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")
                    DebugLogger.log("LITERT", "  wasUsingGPU/NPU=$wasGpuOrNpu  crashedModel=${crashedModelPath.substringAfterLast('/')}")
                    DebugLogger.log("LITERT", "  currentModel=${config.modelPath.substringAfterLast('/')}  sameModel=$crashWasThisModel")
                    DebugLogger.log("LITERT", "  crashCount=${getCrashCount(config.modelPath)}  gpuBanned=${gpuPreviouslyCrashedDuringGen(config.modelPath)}")
                    DebugLogger.log("LITERT", "  batteryOptExempt=$batteryExempt")

                    // Only attribute GPU fault when a *generation* was actively
                    // running on the GPU. wasUsingGpuAtCrash() can be stale from
                    // a prior session's successful gen — gating on
                    // crashedDuringGen here prevents that misattribution.
                    val gpuActuallyAtFault = crashedDuringGen && wasGpuOrNpu && crashWasThisModel

                    val likelyCause = when {
                        gpuActuallyAtFault ->
                            "GPU/NPU_FAULT: inference caused SIGKILL on GPU/NPU backend — banning GPU for 24h."
                        crashedDuringGen && crashWasThisModel ->
                            "CPU_CRASH: Process killed during CPU generation. " +
                            "Possible OEM watchdog, OOM, or native LiteRT issue."
                        crashedDuringInit ->
                            "INIT_CRASH: Killed during model load — likely OOM. " +
                            "Close background apps and retry, or choose a smaller model."
                        else -> "UNKNOWN"
                    }
                    DebugLogger.log("LITERT", "  likelyCause=$likelyCause")
                    DebugLogger.log("LITERT", "=== END CRASH RECOVERY ===")

                    if (crashWasThisModel) {
                        incrementCrashCount(config.modelPath, gpuActuallyAtFault)
                        if (gpuActuallyAtFault) {
                            val convWasReady = wasConvReadyAtCrash()
                            if (convWasReady) {
                                // Crash happened inside sendMessageAsync — GPU actually ran but died
                                // during token generation. Ban GPU for 24h, fall back to CPU.
                                markGpuGenCrashed(config.modelPath)
                                DebugLogger.log("LITERT", "[CRASH] GPU/NPU crashed post-conv-ready (sendMessageAsync) — banning GPU for ${modelKey(config.modelPath)}")
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
                        incrementCrashCount(crashedModelPath, false)
                    }
                    markGenerationEnded()
                    markInitEnded()
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

                // If we have less than 70% of the model's size in free RAM, the mmap will thrash the OS.
                if (profile.availableRamMb < (sizeMb * 0.70)) {
                    throw RuntimeException("Not enough active memory to run this model safely. Please close other apps or use a smaller model.")
                }

                val gpuBanned = gpuPreviouslyCrashedDuringGen(config.modelPath)

                // maxNumTokens = total context window (input + output tokens).
                // 1024 = Google AI Edge Gallery default for all backends including CPU.
                // Reduced to 512 on SM8550 (Snapdragon 8 Gen 2): createConversation() allocates
                // the full KV-cache synchronously. At 1024 tokens the allocation + shader warm-up
                // exceeds the ~5–7s Android process watchdog threshold, causing a SIGKILL before
                // a single token is generated. 512 halves the KV-cache, giving more headroom.
                val cpuCrashCount = getCpuCrashCount(config.modelPath)
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
                val effectiveMaxTokens: Int = run {
                    val headroomMb = profile.availableRamMb - sizeMb
                    val nameForTier = (config.modelName ?: config.modelPath).lowercase()
                    val isLargeTier = nameForTier.contains("gemma 4") ||
                        nameForTier.contains("gemma4") ||
                        nameForTier.contains("gemma-4") ||
                        sizeMb > 1500
                    val isCompactTier = nameForTier.contains("1b") ||
                        nameForTier.contains("compact") ||
                        sizeMb < 700
                    when {
                        // Real CPU crash evidence — keep these as a recovery ladder
                        // since they react to ACTUAL inference instability, not
                        // battery state. Crash counters are cleared on every new
                        // APK install (see version-reset block).
                        // Recovery ladder — but NEVER below a tier's usable
                        // minimum. A Gemma 4 prompt is ~1,100+ tokens; dropping
                        // a LARGE model to 256/64 doesn't "recover" it, it just
                        // swaps the crash for a guaranteed "Input token ids are
                        // too long" on EVERY turn — even a one-word "Hi". This
                        // bricked E4B after a single transient init crash:
                        // maxTokens=256 made normal chat (1051 tok), RAG, and
                        // every attachment fail with 0 tokens generated. For
                        // LARGE the recovery floor is 1536 (KV-cache already 25%
                        // smaller than 2048, and its own prompt still fits); if
                        // even that won't init, the model is marked UNSTABLE
                        // above at cpuCrashCount>=3 with a user-facing message.
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
                        config.maxTokens > 0 && config.maxTokens <= 4096 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=${config.maxTokens} (caller override)")
                            config.maxTokens
                        }
                        isLargeTier && headroomMb >= 2400 -> {
                            // High-end scaling: when the device has ample RAM
                            // headroom, double the window so long multi-turn
                            // chats keep more history before the recap has to
                            // drop turns. KV-cache for E2B at 4096 is only
                            // ~300 MB — comfortably inside a 2400 MB headroom —
                            // and the crash-recovery ladder above still steps
                            // back to 2048/1536 if any device proves unstable.
                            // Mid-range stays at 2048 (next branch); low-RAM at
                            // 1536. No effect on the mid-range primary audience.
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=4096 (LARGE tier, high RAM headroom=${headroomMb}MB — scaled context)  model=${sizeMb}MB")
                            4096
                        }
                        isLargeTier && headroomMb >= 1500 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=2048 (LARGE tier — Gemma 4 needs room for system+recap+reply)  headroom=${headroomMb}MB  model=${sizeMb}MB")
                            2048
                        }
                        isLargeTier -> {
                            // CRITICAL: a Gemma 4 prompt (system + RAG + recap) is
                            // ~1,100–1,450 tokens. The old low-RAM fallback of 512
                            // tokens made EVERY generation fail with "Input token ids
                            // are too long: 1092 >= 512" — no response until the user
                            // restarted (see crash logs). 1536 holds the prompt while
                            // keeping the KV-cache ~25% smaller than 2048 for the
                            // tight-RAM load. Never drop a LARGE model below this.
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1536 (LARGE tier, low RAM headroom=${headroomMb}MB — must fit its own prompt)")
                            1536
                        }
                        isCompactTier -> {
                            // 2048 (was 512): the 512 cap made the Kisan pack fail
                            // on Compact with "Input token ids are too long:
                            // 1484 >= 512" — a Kisan RAG prompt runs ~1500 tokens.
                            // The 1B model's KV-cache at 2048 is only ~55 MB, safe
                            // even on low-RAM devices, and the same SM8550 runs
                            // Gemma 4 E2B at 2048 on GPU without issue. The
                            // crash-recovery ladder above still drops to 256/64 if
                            // any device proves unstable at this size.
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

                // GPU VRAM safety for large models. Tier-aware because the
                // 60 % multiplier was unfairly banning Gemma 4 E4B (3490 MB)
                // from GPU on Galaxy S23+ class devices (12 GB total RAM,
                // ~5.3 GB free) — the model fits comfortably but the gate
                // forced the CPU/XNNPACK path, which dropped throughput
                // from ~17 tps to ~3 tps and gave the "stops responding"
                // impression. Flagship phones hold the rest of total RAM
                // in reclaimable system caches, so we can stretch
                // availableRamMb further without OOM risk.
                val gpuMemMultiplier = when (profile.tier) {
                    com.saarthi.core.inference.model.DeviceTier.FLAGSHIP -> 0.85
                    com.saarthi.core.inference.model.DeviceTier.MID      -> 0.80
                    else                                                 -> 0.60
                }
                val memoryPressureBannedGpu = sizeMb > (profile.availableRamMb * gpuMemMultiplier)
                if (memoryPressureBannedGpu && sizeMb > 2000) {
                    DebugLogger.log("LITERT", "[GPU] BANNED: Model size (${sizeMb}MB) > availRam (${profile.availableRamMb}MB) × ${gpuMemMultiplier} on tier=${profile.tier}. Falling back to CPU.")
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

                markInitStarted(config.modelPath)
                markStage(CrashStage.MODEL_LOAD)
                try {
                    val newEngine = tryLoadWithFallback(
                        modelPath = config.modelPath,
                        maxTokens = effectiveMaxTokens,
                        profile = profile,
                        gpuBanned = gpuBanned || memoryPressureBannedGpu,
                        dynamicThreads = dynamicThreads,
                        xnnpackBanned = xnnpackBanned,
                    )
                    
                    // CRITICAL: Save the active backend state BEFORE we do any heavy operations
                    // like createConversation. If the native driver SIGKILLs during the next step,
                    // the Crash Recovery system will correctly see that the GPU was active and ban it.
                    enginePrefs.edit()
                        .putBoolean("litert_was_using_gpu", usingGpu || usingNpu)
                        .commit()


                    
                    // Undo lazy init per research: create conversation synchronously during init
                    DebugLogger.log("LITERT", "[INIT] Engine loaded. Creating conversation matrix synchronously...")
                    val samplerConfig = samplerFor(config.modelPath)
                    markStage(CrashStage.CREATE_CONVERSATION)
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
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    _isFreshConversation = true   // brand-new Conversation, no turns in KV
                    markInitEnded()
                    // ── Stale-ban self-heal ──────────────────────────────
                    // If a previous session left a GPU ban + crash count on
                    // this model (e.g. from a misattributed JVM crash), the
                    // very fact that init just completed proves the device
                    // can load it. Clear both so the next session tries GPU
                    // again instead of permanently downgrading to CPU.
                    runCatching {
                        if (gpuPreviouslyCrashedDuringGen(config.modelPath)) {
                            DebugLogger.log("LITERT", "[RECOVERY] Init succeeded — clearing stale GPU ban for ${modelKey(config.modelPath)}")
                            clearGpuGenCrashedFlag(config.modelPath)
                        }
                        if (getCrashCount(config.modelPath) > 0) {
                            DebugLogger.log("LITERT", "[RECOVERY] Init succeeded — resetting stale crash count")
                            resetCrashCount(config.modelPath)
                        }
                    }
                    setReady(true)
                    DebugLogger.log("LITERT", "Model ready & pre-warmed  $profile  backend=${backendLabel()}")
                } catch (e: Throwable) {
                    markInitEnded()
                    val rawMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    val msg = when {
                        e is OutOfMemoryError ->
                            "Not enough RAM to load this model. Close background apps and try again, or choose a smaller model."
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

        // ── NPU (Qualcomm QNN — SM8750/SM8550 with device-specific .litertlm) ──
        // Only attempted when the SoC allows NPU AND the model file has QNN-compiled
        // layers for this SoC. Generic bundles have no QNN layers and throw immediately.
        if (profile.npuSafe && !gpuBanned && modelNpuCompatible) {
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
        if (profile.gpuSafe && !gpuBanned &&
            (profile.safeModelBudgetMb * 1_048_576L) >= maxTokens.toLong()) {
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
                gpuBanned        -> "prior GPU/NPU crash ban active for ${modelKey(modelPath)}"
                !profile.gpuSafe -> "gpuSafe=false — SoC=${profile.socModel} API=${profile.apiLevel}"
                else             -> "model too large for GPU memory budget"
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
        e.initialize()  // blocking — must be called on background thread
        return e
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
            val fresh = runCatching {
                eng.createConversation(ConversationConfig(samplerConfig = sampler))
            }.getOrNull()
            activeConversation = fresh
            _isFreshConversation = true
            fresh
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
                markGenerationStarted()

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
                val groundedNow = isGroundedPrompt(prompt)
                val desiredSampler = if (groundedNow) groundedSamplerFor(loadedModelPath ?: "")
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

                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val cleaned = message.toString().filterSpecialTokens()
                        if (cleaned.isNotEmpty()) {
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
                        CoroutineScope(Dispatchers.IO).launch {
                            val sc = if (conversationIsGrounded) groundedSamplerFor(loadedModelPath ?: "")
                                     else samplerForActiveModel()
                            recycleConversation(sc)
                            thisDone.complete(Unit)
                        }

                        resetCrashCount(loadedModelPath ?: "")
                        markGenerationEnded()
                        val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                        val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                        DebugLogger.log("LITERT", "Stream done  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}  conv=recycled")
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
                        CoroutineScope(Dispatchers.IO).launch {
                            val sc = if (conversationIsGrounded) groundedSamplerFor(loadedModelPath ?: "")
                                     else samplerForActiveModel()
                            recycleConversation(sc)
                            thisDone.complete(Unit)
                        }

                        markGenerationEnded()

                        // cancelProcess() triggers CancellationException — treat as
                        // normal stop (user navigated away), not as an error.
                        if (error is java.util.concurrent.CancellationException || error is CancellationException) {
                            val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                            val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                            DebugLogger.log("LITERT", "Stream cancelled  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}")
                            resetCrashCount(loadedModelPath ?: "")
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

    // ── generate (one-shot) ───────────────────────────────────────────────────

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(engineDispatcher) {
            val eng = engine ?: throw IllegalStateException("LiteRT engine not initialised.")
            DebugLogger.log("LITERT", "Generate start  promptChars=${prompt.length}")
            // Mirror streamResponse's foreground-service guard so the OEM
            // power-manager (Samsung OneUI / Xiaomi MIUI in particular) can't
            // kill the process while a long one-shot generate runs. Safe to
            // call repeatedly — startGenerating just updates the notification
            // state. The matching stop() is called in the finally block.
            com.saarthi.core.inference.InferenceService.startGenerating(context)
            generateMutex.withLock {
                isGenerating = true
                markGenerationStarted()
                try {
                    val samplerConfig = samplerForActiveModel()
                    val conv = eng.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                    try {
                        val deferred = CompletableDeferred<String>()
                        val sb = StringBuilder()
                        conv.sendMessageAsync(prompt, object : MessageCallback {
                            override fun onMessage(m: Message) {
                                sb.append(m.toString().filterSpecialTokens())
                            }
                            override fun onDone() { deferred.complete(sb.toString()) }
                            override fun onError(e: Throwable) { deferred.completeExceptionally(e) }
                        })
                        deferred.await()
                    } finally {
                        runCatching { conv.close() }
                    }
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                    Timber.e(e, "LiteRT generation failed")
                    throw RuntimeException(msg, e)
                } finally {
                    isGenerating = false
                    markGenerationEnded()
                    runCatching { com.saarthi.core.inference.InferenceService.stop(context) }
                }
            }
        }

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
        markStage(CrashStage.CLEANUP)
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
