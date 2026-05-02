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
 *   1. ChatRepositoryImpl calls [start] before streaming tokens.
 *   2. The service posts a low-priority notification ("Thinking…").
 *   3. When generation is done (or the chat screen is destroyed), [stop] is called.
 *
 * This service does NOT host the inference engine itself — it only holds
 * the foreground wakelocks. The engine is a Hilt singleton injected elsewhere.
 */
@AndroidEntryPoint
class InferenceService : Service() {

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
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is the correct and only type for
                // on-device AI inference. DATA_SYNC was removed in Android 16 (API 36) —
                // passing it here causes SecurityException on API 36+ devices (e.g. Samsung
                // SM-S918B), which was silently caught below, leaving us with NO foreground
                // service protection and the LMK killing the process in ~10 seconds.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14+ is extremely strict about when an FGS can start.
            // If it fails, log the reason prominently — silent failure here is the #1
            // crash source (process gets killed mid-inference with no stack trace).
            DebugLogger.log("SERVICE", "WARN: startForeground failed (${e.javaClass.simpleName}): ${e.message}")
            // Attempt graceful fallback: try without an explicit type (API < Q behaviour)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }

        // Acquire a partial wake lock to keep the CPU running during decode
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saarthi:inference_fg")
            .also {
                it.setReferenceCounted(false)
                it.acquire(10 * 60 * 1000L) // 10 min max safety timeout
            }

        DebugLogger.log("SERVICE", "Foreground inference service started")
        return START_NOT_STICKY
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
        
        DebugLogger.log("SERVICE", "Foreground inference service stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Inference",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps Saarthi alive while the AI model is generating a response."
                setShowBadge(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Saarthi is thinking…")
            .setContentText("Processing your message offline.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "saarthi_inference"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            val intent = Intent(context, InferenceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }
    }
}
