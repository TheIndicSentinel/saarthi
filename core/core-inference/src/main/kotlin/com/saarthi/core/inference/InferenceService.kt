package com.saarthi.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.saarthi.core.inference.engine.InferenceEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground Service that keeps the process alive during heavy AI inference.
 *
 * On Android 14+ (and Samsung OneUI 6/7), the system aggressively kills
 * foreground apps that consume sustained CPU/GPU without a Foreground Service.
 * Running inference inside this service prevents the LMK (Low-Memory Killer)
 * and the power watchdog from terminating the process mid-decode.
 *
 * Lifecycle:
 *   1. LiteRTInferenceEngine calls [startLoading] before model loading begins.
 *   2. ChatRepositoryImpl calls [startGenerating] before streaming tokens — updates
 *      the notification to show "Generating response…" text.
 *   3. When generation is done (or the chat screen is destroyed), [stop] is called
 *      from ChatRepositoryImpl (normal path) or from the native 'done' callback in
 *      LiteRTInferenceEngine (timeout/cancel path — keeps FGS alive for the native
 *      GPU thread, only stopping once the native computation actually finishes).
 *
 * Notification states:
 *   LOADING    — "Loading AI model…"   shown while model is being loaded from disk
 *   GENERATING — "Generating response…" shown during active inference
 *
 * This service does NOT host the inference engine itself — it only holds
 * the foreground wake-locks and exposes state-based notification updates.
 */
@AndroidEntryPoint
class InferenceService : Service() {

    enum class NotificationState { LOADING, GENERATING }

    @Inject lateinit var inferenceEngine: InferenceEngine

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.getStringExtra(EXTRA_STATE)
            ?.let { runCatching { NotificationState.valueOf(it) }.getOrNull() }
            ?: NotificationState.GENERATING

        val notification = buildNotification(state)
        if (!enterForeground(notification)) {
            // We could NOT legally become a foreground service right now — e.g. on
            // a low-RAM budget device the app was pushed to the background during a
            // memory-pressure storm (the Android 13 SM-E625F log showed exactly
            // this). If we leave the startForegroundService() promise unfulfilled,
            // the platform fires ForegroundServiceDidNotStartInTimeException and
            // HARD-CRASHES the process. Stopping now turns that crash into a clean
            // no-op: inference still runs on its own thread, it just loses FGS
            // protection for this one turn — far better than crashing on low-end
            // devices.
            DebugLogger.log("SERVICE", "FGS could not enter foreground — stopping to avoid crash")
            stopSelf()
            return START_NOT_STICKY
        }

        // Acquire a partial wake lock to keep the CPU running during decode
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saarthi:inference_fg").apply {
                setReferenceCounted(false)
            }
        }
        if (!wakeLock!!.isHeld) {
            wakeLock!!.acquire(10 * 60 * 1000L) // 10 min max safety timeout
        }

        DebugLogger.log("SERVICE", "FGS onStartCommand  state=$state")
        return START_NOT_STICKY
    }

    /**
     * Enter the foreground, returning true only if [startForeground] actually
     * succeeded. Returns false (instead of swallowing) so the caller can stop
     * the service rather than leave the start-promise dangling → crash.
     *
     * FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API-34 type; on API 29–33 the
     * manifest's `specialUse` value is unknown, so passing the typed flag there
     * throws. Use the untyped overload (valid on every API) below 34 — that's
     * the path the budget-device API-33 log needs.
     */
    private fun enterForeground(notification: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: Exception) {
            // #1 crash source if swallowed: process killed mid-inference with no
            // stack trace. Log loudly, then make one untyped attempt (covers an
            // API-34 type/manifest mismatch) before giving up.
            DebugLogger.log("SERVICE", "WARN: startForeground failed (${e.javaClass.simpleName}): ${e.message}")
            return runCatching {
                startForeground(NOTIFICATION_ID, notification)
                true
            }.getOrElse {
                DebugLogger.log("SERVICE", "WARN: untyped startForeground also failed: ${it.message}")
                false
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        DebugLogger.log("SERVICE", "FGS stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Inference",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Saarthi alive while the AI model is generating a response."
                setShowBadge(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: NotificationState): Notification {
        val (title, text) = when (state) {
            NotificationState.LOADING   -> "Saarthi is loading a model…" to "Preparing the AI model. This takes a few seconds."
            NotificationState.GENERATING -> "Saarthi is generating a response…" to "Processing your message offline."
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        internal const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "saarthi_inference"
        private const val EXTRA_STATE = "notification_state"

        /** Call before model loading begins — shows "Loading AI model…" notification. */
        fun startLoading(context: Context) = startWithState(context, NotificationState.LOADING)

        /**
         * Call before inference streaming begins — shows "Generating response…" notification.
         * If the service is already running (e.g. from model loading), the notification is
         * updated in place via [startForeground] being called again in [onStartCommand].
         */
        fun startGenerating(context: Context) = startWithState(context, NotificationState.GENERATING)

        private fun startWithState(context: Context, state: NotificationState) {
            val intent = Intent(context, InferenceService::class.java)
                .putExtra(EXTRA_STATE, state.name)
            // BEST-EFFORT. On Android 12+ startForegroundService() throws
            // ForegroundServiceStartNotAllowedException when the app is in the
            // background — e.g. the user left the app during a long (2.5 GB)
            // model download and init fires on completion with the screen off.
            // This service is ONLY a keep-alive + status notification; the model
            // load and inference run on their own coroutines regardless. A
            // blocked start must therefore NOT fail the load (it previously
            // surfaced as "Model load failed: startForegroundService() not
            // allowed due to mAllowStartForeground false"). Swallow it and go on.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                DebugLogger.log(
                    "SERVICE",
                    "FGS start skipped (${e.javaClass.simpleName}: ${e.message}) — " +
                        "continuing without keep-alive service",
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }

        /**
         * Cancels any stale notification left over from a previous session that ended
         * via SIGKILL (process killed mid-inference, so [onDestroy] was never called).
         * Call from Application.onCreate to clean up before the first session starts.
         */
        fun cancelStaleNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
