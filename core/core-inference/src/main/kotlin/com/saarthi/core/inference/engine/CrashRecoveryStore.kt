package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.SocFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted crash-detection/recovery state for [LiteRTInferenceEngine] —
 * extracted verbatim (same SharedPreferences keys, same commit-vs-apply
 * choice per call, same defaults) out of that class, which had accumulated
 * this alongside app-lifecycle management, backend/token-budget decisions,
 * and streaming orchestration in one file. This class does ONE thing:
 * read/write the crash-tracking SharedPreferences.
 *
 * Deliberately mechanical, not a rewrite: this logic has been tuned through
 * numerous real field incidents (see the per-method comments, several of
 * which cite exact crash log lines and device models) and this project has
 * no Robolectric/instrumented-test coverage to validate crash-recovery
 * timing on a real device. The commit()-vs-apply() choice on every write
 * below is preserved exactly as it was — commit() is used deliberately on
 * anything that must survive a SIGKILL a few milliseconds later, apply() on
 * writes that only need to land eventually.
 *
 * Two methods below don't map 1:1 to the original private functions:
 * [breakCrashLoopIfNeeded] originally also set [LiteRTInferenceEngine]'s own
 * `crashLoopBlocked` field directly; that side effect now belongs to the
 * caller (this class doesn't know about that field), which reacts to this
 * method's return value instead. [markGenerationStarted] originally read
 * `usingGpu`/`usingNpu`/`loadedModelPath` directly off the engine instance;
 * those are now parameters the caller supplies. Both are behavior-preserving
 * — only where the values come from changed, not what gets written.
 *
 * Crash detection: synchronous SharedPrefs writes survive process kills.
 *   litert_gen_pending      — set during generation; true at startup → crashed mid-response
 *   litert_init_pending     — set during model init; true at startup → crashed mid-load (OOM)
 *   litert_was_using_gpu    — which backend was active at crash (GPU/NPU = true)
 *   litert_crash_model_path — which model was generating at crash
 *   litert_conv_ready       — false during createConversation(), true after; crash while false = don't ban GPU
 *   litert_crash_count_*    — per-model consecutive crash count
 *   litert_gpu_ban_*        — per-model GPU ban flag (true = use CPU for 24h)
 *   litert_gpu_ban_ts_*     — per-model GPU ban timestamp
 *   litert_gpu_ban_soc_*    — per-SoC-family GPU ban flag (true = use CPU for 24h)
 *   litert_gpu_ban_soc_ts_* — per-SoC-family GPU ban timestamp
 *   litert_crash_stage      — last CrashStage entered (for recovery diagnostics)
 *   saarthi_last_crash_was_jvm / saarthi_last_crash_class — set by SaarthiApp's
 *     uncaught-exception handler; a JVM-side Throwable is not the engine's fault.
 */
@Singleton
class CrashRecoveryStore private constructor(
    private val prefs: SharedPreferences,
    private val context: Context?,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefs = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE),
        context = context,
    )

    /** JVM unit tests — in-memory prefs, no Robolectric. */
    internal constructor(prefs: SharedPreferences) : this(prefs, context = null)

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
    //
    // On each new APK install, clear all per-session crash tracking so stale
    // crash counts from a previous build don't trigger the crash loop
    // blocker on first run. GPU bans are also cleared — a new build may have
    // different backend config. Called once from LiteRTInferenceEngine's own
    // init block, passing the resolved app version code.
    fun resetOnVersionChange(currentVersion: Int) {
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

    fun wasKilledDuringGeneration() =
        prefs.getBoolean("litert_gen_pending", false)

    fun wasKilledDuringInit() =
        prefs.getBoolean("litert_init_pending", false)

    /**
     * Whether Android itself attributes the most recent time this process
     * died to REASON_LOW_MEMORY — confirmed OS evidence, not just "the
     * process died while litert_gen_pending/litert_init_pending was set"
     * (which can't distinguish an LMK kill from a user force-stop, an ANR,
     * or an unrelated native crash the same way this can). Purely additive
     * to the existing dead-man's-switch crash detection above: it only
     * upgrades the CONFIDENCE of a diagnosis already made by
     * wasKilledDuringGeneration()/wasKilledDuringInit(), never triggers
     * recovery on its own. API 30+ only (getHistoricalProcessExitReasons);
     * returns false below that, so older devices keep exactly today's
     * behavior.
     */
    fun lastExitWasConfirmedLowMemory(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val ctx = context ?: return false
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val reasons = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1)
            reasons.firstOrNull()?.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
        }.getOrDefault(false)
    }

    fun modelKey(modelPath: String) = modelPath.substringAfterLast('/')

    /**
     * Returns the per-model consecutive-crash count, automatically expiring
     * it after [CRASH_COUNT_EXPIRY_MS] of no new crashes.
     *
     * Legacy state from older APK versions has no timestamp; we stamp it
     * on first read so the 24-hour clock starts then. After expiry the
     * count is wiped so the user gets a clean attempt without a reinstall.
     */
    fun getCrashCount(modelPath: String): Int =
        readExpiringCount("litert_crash_count_${modelKey(modelPath)}")

    fun getCpuCrashCount(modelPath: String): Int =
        readExpiringCount("litert_cpu_crash_count_${modelKey(modelPath)}")

    private fun readExpiringCount(baseKey: String): Int {
        val count = prefs.getInt(baseKey, 0)
        if (count == 0) return 0
        val tsKey = "${baseKey}_ts"
        val ts = prefs.getLong(tsKey, 0L)
        if (ts == 0L) {
            // Legacy state from before timestamp tracking — stamp it now
            // so the expiry clock starts from this read.
            prefs.edit().putLong(tsKey, System.currentTimeMillis()).apply()
            return count
        }
        val ageMs = System.currentTimeMillis() - ts
        if (ageMs >= CRASH_COUNT_EXPIRY_MS) {
            DebugLogger.log(
                "LITERT",
                "$baseKey expired after ${ageMs / 3_600_000}h — clearing (auto-recovery)",
            )
            prefs.edit().remove(baseKey).remove(tsKey).apply()
            return 0
        }
        return count
    }

    fun incrementCrashCount(modelPath: String, wasGpuOrNpu: Boolean) {
        val key = modelKey(modelPath)
        val now = System.currentTimeMillis()
        val count = getCrashCount(modelPath) + 1
        val editor = prefs.edit()
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

    fun resetCrashCount(modelPath: String) {
        val key = modelKey(modelPath)
        prefs.edit()
            .remove("litert_crash_count_$key")
            .remove("litert_crash_count_${key}_ts")
            .apply()
    }

    fun gpuPreviouslyCrashedDuringGen(modelPath: String): Boolean {
        val key = modelKey(modelPath)
        if (!prefs.getBoolean("litert_gpu_ban_$key", false)) return false
        val bannedAt = prefs.getLong("litert_gpu_ban_ts_$key", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true
        } else {
            DebugLogger.log("LITERT", "GPU ban expired for $key after ${banAgeMs / 3_600_000}h — retrying GPU")
            clearGpuGenCrashedFlag(modelPath)
            false
        }
    }

    /**
     * Returns true when a crash loop (≥4 consecutive crashes) was detected
     * for [modelPath] — the in-flight SharedPrefs trackers are reset here so
     * the next attempt doesn't double-count, but the caller is responsible
     * for its own `crashLoopBlocked` state (this class has no such field).
     */
    fun breakCrashLoopIfNeeded(modelPath: String): Boolean {
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
            prefs.edit()
                .putBoolean("litert_gen_pending", false)
                .putBoolean("litert_init_pending", false)
                .putBoolean("litert_gpu_ban_$key", false)
                .putLong("litert_gpu_ban_ts_$key", 0L)
                .commit()
            return true
        }
        return false
    }

    fun markGpuGenCrashed(modelPath: String) {
        val key = modelKey(modelPath)
        prefs.edit()
            .putBoolean("litert_gpu_ban_$key", true)
            .putLong("litert_gpu_ban_ts_$key", System.currentTimeMillis())
            .commit()
    }

    fun clearGpuGenCrashedFlag(modelPath: String) {
        val key = modelKey(modelPath)
        prefs.edit()
            .putBoolean("litert_gpu_ban_$key", false)
            .putLong("litert_gpu_ban_ts_$key", 0L)
            .apply()
    }

    // ── Per-SoC-family GPU ban ────────────────────────────────────────────
    //
    // The per-model ban above (litert_gpu_ban_$key) means a GPU/NPU crash on
    // Model A doesn't inform whether GPU is trustworthy for Model B on the
    // very same device — each model file starts with a clean slate even
    // though the actual fault (an OEM driver bug) lives at the SoC level,
    // not the model. Since socFamily is a fixed hardware fact for a given
    // device (see DeviceProfiler.classifySoc), a crash attributed to
    // GPU/NPU now ALSO bans GPU for every other model on this SoC family,
    // for the same 24h window — set alongside, not instead of, the
    // per-model ban. This is still purely on-device, per-app-install state
    // (no data leaves the phone) — it doesn't generalize "this Dimensity
    // generation is safe" across DIFFERENT physical devices, only across
    // different MODELS on the one device that just crashed.
    fun gpuFamilyPreviouslyCrashedDuringGen(socFamily: SocFamily): Boolean {
        val key = socFamily.name
        if (!prefs.getBoolean("litert_gpu_ban_soc_$key", false)) return false
        val bannedAt = prefs.getLong("litert_gpu_ban_soc_ts_$key", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true
        } else {
            DebugLogger.log("LITERT", "GPU family-ban expired for $key after ${banAgeMs / 3_600_000}h — retrying GPU")
            clearGpuGenCrashedFlagForSoc(socFamily)
            false
        }
    }

    fun markGpuGenCrashedForSoc(socFamily: SocFamily) {
        val key = socFamily.name
        prefs.edit()
            .putBoolean("litert_gpu_ban_soc_$key", true)
            .putLong("litert_gpu_ban_soc_ts_$key", System.currentTimeMillis())
            .commit()
    }

    fun clearGpuGenCrashedFlagForSoc(socFamily: SocFamily) {
        val key = socFamily.name
        prefs.edit()
            .putBoolean("litert_gpu_ban_soc_$key", false)
            .putLong("litert_gpu_ban_soc_ts_$key", 0L)
            .apply()
    }

    fun markInitStarted(modelPath: String) {
        prefs.edit()
            .putBoolean("litert_init_pending", true)
            .putString("litert_crash_model_path", modelPath)
            .commit()
    }

    fun markInitEnded() =
        prefs.edit().putBoolean("litert_init_pending", false).commit()

    /**
     * @param wasUsingGpuOrNpu caller's `usingGpu || usingNpu` at the moment
     *   generation starts — this class holds no engine backend state itself.
     * @param modelPath caller's `loadedModelPath` (or "" if null).
     */
    fun markGenerationStarted(wasUsingGpuOrNpu: Boolean, modelPath: String) {
        prefs.edit()
            .putBoolean("litert_gen_pending", true)
            .putBoolean("litert_was_using_gpu", wasUsingGpuOrNpu)
            .putString("litert_crash_model_path", modelPath)
            .commit()
    }

    fun markGenerationEnded() =
        prefs.edit().putBoolean("litert_gen_pending", false).commit()

    fun wasUsingGpuAtCrash() =
        prefs.getBoolean("litert_was_using_gpu", false)

    /**
     * Records which backend is active right after a model load completes —
     * distinct from [markGenerationStarted] (which also flips
     * litert_gen_pending, deliberately NOT wanted here since this fires
     * before generation, at load time) but writes the same
     * litert_was_using_gpu key, so a SIGKILL during the immediately-following
     * createConversation() is still correctly attributed.
     */
    fun recordBackendForCrashAttribution(wasUsingGpuOrNpu: Boolean) {
        prefs.edit()
            .putBoolean("litert_was_using_gpu", wasUsingGpuOrNpu)
            .commit()
    }

    fun markConvStarted() =
        prefs.edit().putBoolean("litert_conv_ready", false).commit()

    fun markConvReady() =
        prefs.edit().putBoolean("litert_conv_ready", true).commit()

    // Default true = conservative (unknown crash assumed post-conv → ban GPU).
    // markConvStarted() sets false before createConversation(); markConvReady() sets true after.
    // A crash in createConversation() leaves the pref false → GPU is NOT banned on next run
    // (second run has cached shaders, may complete in time).
    fun wasConvReadyAtCrash() =
        prefs.getBoolean("litert_conv_ready", true)

    fun markStage(stage: CrashStage) {
        prefs.edit().putString("litert_crash_stage", stage.name).commit()
        DebugLogger.log("LITERT", "[STAGE] Entering stage: $stage")
    }

    fun crashStageRaw(): String = prefs.getString("litert_crash_stage", "UNKNOWN") ?: "UNKNOWN"

    fun crashedModelPath(): String = prefs.getString("litert_crash_model_path", "") ?: ""

    // ── JVM-crash filter ──────────────────────────────────────────────────
    // SaarthiApp's uncaught-exception handler stamps these before the
    // process dies. A JVM-side Throwable is not the inference engine's
    // fault — without this check, an unrelated JVM crash would be seen as
    // a stale `litert_was_using_gpu=true` from a previous session's
    // successful generation, and ban the GPU for 24h on a healthy device.

    fun lastCrashWasJvm(): Boolean = prefs.getBoolean("saarthi_last_crash_was_jvm", false)

    fun lastCrashClass(): String = prefs.getString("saarthi_last_crash_class", "?") ?: "?"

    /** Clears the JVM-crash markers and the pending flags they short-circuit. */
    fun clearJvmCrashState() {
        prefs.edit()
            .remove("saarthi_last_crash_was_jvm")
            .remove("saarthi_last_crash_class")
            .putBoolean("litert_init_pending", false)
            .putBoolean("litert_gen_pending", false)
            .commit()
    }
}
