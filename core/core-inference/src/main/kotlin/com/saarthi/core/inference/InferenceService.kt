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
                // Include both specialUse (AI) and dataSync (Processing) for maximum resilience.
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14+ is extremely strict about when an FGS can start.
            // If it fails, log it and proceed as a background service.
            // The WakeLock below will still provide some protection.
            DebugLogger.log("SERVICE", "Failed to start as Foreground: ${e.message}")
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

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Saarthi is processing your message")
            .setContentText("AI response generation is in progress.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
