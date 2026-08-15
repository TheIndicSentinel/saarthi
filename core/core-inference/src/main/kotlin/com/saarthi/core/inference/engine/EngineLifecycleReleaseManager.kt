package com.saarthi.core.inference.engine

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.saarthi.core.inference.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced two-stage background release scheduling for
 * [LiteRTInferenceEngine] — extracted (same guard conditions, same
 * two-stage design) as part of the C3 God-class reduction. Delays are
 * RAM-tiered via [delaysForTotalRamMb].
 *
 * Previously the engine only released its native/mmap/GPU footprint under
 * actual memory-pressure signals (onTrimMemory RUNNING_CRITICAL/COMPLETE,
 * onLowMemory — still handled by the engine itself via ComponentCallbacks2,
 * unrelated to this class). Those fire late, if at all: many OEM skins kill
 * background apps via their own process-killer well before Android's own
 * trim ladder escalates that far, so a multi-GB model engine could sit
 * fully resident for the entire time the app was backgrounded on exactly
 * the 6-8GB devices with the least room to spare for it.
 *
 * ComponentCallbacks2 has no "app is visible again" signal (only
 * increasing-pressure/backgrounding levels), so detecting the OTHER edge —
 * for the debounce below — needs Application.ActivityLifecycleCallbacks
 * instead: visibleActivityCount transitions 0→1 on return to foreground
 * (cancels the pending release) and 1→0 on backgrounding (schedules it).
 * This tracks the same "any activity visible" signal
 * androidx.lifecycle.ProcessLifecycleOwner would, done manually here so
 * core-inference doesn't need a new dependency for it.
 *
 * This class owns the ActivityLifecycleCallbacks registration and the
 * release-SCHEDULING bookkeeping (debounce timers, visibility counting). It
 * does NOT own the actual native release logic — releasing a live
 * Engine/Conversation needs deep access to LiteRTInferenceEngine's own
 * mutable state (activeConversation, engine, closingEngine, ...) that has
 * no business living in a lifecycle-timing class. [releaseConversationOnly]
 * and [releaseEngine] are supplied by the caller as suspend lambdas for
 * exactly that reason.
 *
 * H13 fix folded into this extraction: both lambdas are expected to hop to
 * the engine's own single-threaded dispatcher before doing any blocking
 * native/SharedPreferences work (see [LiteRTInferenceEngine]'s
 * construction of this class) — previously that work ran directly on
 * [lifecycleScope], which is `Dispatchers.Main.immediate`, meaning a
 * backgrounding event could block the main thread with native
 * Engine/Conversation close() calls and a synchronous SharedPreferences
 * commit(). This class doesn't enforce that itself (it has no dispatcher
 * opinion of its own — it just awaits whatever suspend lambda it's given),
 * but its kdoc and the caller's wiring make the requirement explicit.
 */
class EngineLifecycleReleaseManager(
    private val context: Context,
    private val totalRamMb: () -> Long,
    private val isNativeGenerating: () -> Boolean,
    private val isInitInProgress: () -> Boolean,
    private val releaseConversationOnly: suspend () -> Unit,
    private val releaseEngine: suspend () -> Unit,
) {
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var pendingBackgroundRelease: Job? = null
    @Volatile private var pendingConversationRelease: Job? = null
    @Volatile private var visibleActivityCount = 0

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivityCount++
            if (visibleActivityCount == 1) {
                pendingConversationRelease?.cancel()
                pendingConversationRelease = null
                pendingBackgroundRelease?.cancel()
                pendingBackgroundRelease = null
            }
        }
        override fun onActivityStopped(activity: Activity) {
            visibleActivityCount = (visibleActivityCount - 1).coerceAtLeast(0)
            if (visibleActivityCount == 0) {
                val ramMb = totalRamMb()
                val delays = delaysForTotalRamMb(ramMb)
                DebugLogger.log(
                    "LITERT",
                    "App backgrounded — scheduling two-stage release " +
                        "(conv=${delays.conversationReleaseDelayMs / 1000}s, " +
                        "engine=${delays.engineReleaseDelayMs / 1000}s, totalRam=${ramMb}MB)",
                )
                scheduleConversationRelease(delays.conversationReleaseDelayMs)
                scheduleBackgroundRelease(delays.engineReleaseDelayMs)
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * @ApplicationContext resolves to the actual Application instance —
     * registerActivityLifecycleCallbacks is an Application-only method
     * (unlike ComponentCallbacks2 registration, which any Context
     * supports, handled separately by the engine itself). The safe cast
     * mirrors the original inline code exactly: silently no-ops if
     * [context] somehow isn't an Application, same as before this
     * extraction.
     */
    fun register() {
        (context as? Application)?.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
    }

    /**
     * Stage 1 of 2 on backgrounding: release just the Conversation (its
     * KV-cache) after [conversationDelayMs], well before the full Engine
     * release in [scheduleBackgroundRelease]. The Conversation is cheap to
     * recreate (~30-50ms with cached shaders — generateStream()'s existing
     * "activeConversation == null" fallback, already used for the very
     * first turn after init, does this transparently on the next
     * generation) while the mmap'd Engine (model weights) is the expensive
     * part to reload (~5-10s). Freeing the cheap-to-rebuild resource
     * sooner gets real memory back faster without making every return-to-
     * app pay the full reload cost the single-stage release used to.
     *
     * Delays are RAM-tiered via [delaysForTotalRamMb]: tighter on ≤6 GB
     * phones so OEM killers are less likely to reclaim the process while a
     * multi-GB mmap footprint is still resident. Foreground cancel +
     * generation/init skip guards are unchanged.
     */
    private fun scheduleConversationRelease(conversationDelayMs: Long) {
        pendingConversationRelease?.cancel()
        pendingConversationRelease = lifecycleScope.launch {
            delay(conversationDelayMs)
            if (!isNativeGenerating() && !isInitInProgress()) {
                DebugLogger.log("LITERT",
                    "App backgrounded for ${conversationDelayMs / 1000}s — releasing Conversation (KV-cache), engine stays resident")
                releaseConversationOnly()
            } else {
                DebugLogger.log("LITERT", "Conversation release skipped — generation or load still in progress")
            }
        }
    }

    /**
     * Stage 2 of 2 on backgrounding — see [scheduleConversationRelease]'s
     * kdoc for why this is split into two stages. Debounced, not
     * immediate: a quick app-switch (checking a notification, glancing at
     * another app) shouldn't pay the ~5-10s GPU reload cost the next time
     * the user returns. [engineDelayMs] is RAM-tiered (see
     * [delaysForTotalRamMb]); generation/init skip guards are unchanged.
     */
    private fun scheduleBackgroundRelease(engineDelayMs: Long) {
        pendingBackgroundRelease?.cancel()
        pendingBackgroundRelease = lifecycleScope.launch {
            delay(engineDelayMs)
            // Never interrupt an in-flight generation — closeInternal()'s
            // existing deferred-close path handles a concurrent close
            // safely, but a generation the user is actively waiting on
            // finishing in the background shouldn't be torn down just
            // because they alt-tabbed away. Also skip while a model load is
            // in flight (isInitInProgress) — closeInternal() has no
            // equivalent deferred-close guard for that phase, so closing
            // the Engine out from under an active initialize() call could
            // race. Both are narrow windows (generation/load are seconds,
            // this delay is tens of seconds), so skipping this cycle and
            // trying again on the next backgrounding (or an actual
            // memory-pressure callback) is safe.
            if (!isNativeGenerating() && !isInitInProgress()) {
                DebugLogger.log("LITERT",
                    "App backgrounded for ${engineDelayMs / 1000}s — releasing engine")
                releaseEngine()
            } else {
                DebugLogger.log("LITERT", "Background release skipped — generation or load still in progress")
            }
        }
    }

    companion object {
        /** Matches [com.saarthi.core.inference.model.DeviceProfile] LOW vs MID cutoff. */
        internal const val LOW_RAM_THRESHOLD_MB = 6_000L
        internal const val CONVERSATION_RELEASE_DELAY_LOW_RAM_MS = 30_000L
        internal const val ENGINE_RELEASE_DELAY_LOW_RAM_MS = 90_000L
        internal const val CONVERSATION_RELEASE_DELAY_HIGH_RAM_MS = 60_000L
        internal const val ENGINE_RELEASE_DELAY_HIGH_RAM_MS = 120_000L

        /**
         * Conservative two-stage delays from total RAM (stable hardware spec,
         * not fluctuating available RAM). Phones below 6 GB shed KV-cache
         * and the full engine sooner so OEM background killers are less
         * likely to SIGKILL a still-resident mmap footprint. Higher-RAM
         * devices keep the original 60s / 120s window to avoid extra reloads
         * on brief app switches.
         */
        internal fun delaysForTotalRamMb(totalRamMb: Long): BackgroundReleaseDelays =
            if (totalRamMb < LOW_RAM_THRESHOLD_MB) {
                BackgroundReleaseDelays(
                    conversationReleaseDelayMs = CONVERSATION_RELEASE_DELAY_LOW_RAM_MS,
                    engineReleaseDelayMs = ENGINE_RELEASE_DELAY_LOW_RAM_MS,
                )
            } else {
                BackgroundReleaseDelays(
                    conversationReleaseDelayMs = CONVERSATION_RELEASE_DELAY_HIGH_RAM_MS,
                    engineReleaseDelayMs = ENGINE_RELEASE_DELAY_HIGH_RAM_MS,
                )
            }
    }
}

internal data class BackgroundReleaseDelays(
    val conversationReleaseDelayMs: Long,
    val engineReleaseDelayMs: Long,
)
