package com.saarthi.app

import android.app.Application
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.InferenceService
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class SaarthiApp : Application() {

    @Inject lateinit var reminderManager: ReminderManager

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        DebugLogger.init(this)
        // Remove any stale inference notification left from a previous session that ended
        // via SIGKILL (onDestroy was never called, so the FGS notification persists on
        // Samsung OneUI even though the process is gone).
        InferenceService.cancelStaleNotification(this)
        installCrashLogger()
        // Ensures the notification channel exists before any reminder fires
        reminderManager.createNotificationChannel()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DebugLogger.log("CRASH", "UNCAUGHT on thread=${thread.name}")
                DebugLogger.log("CRASH", "Type: ${throwable.javaClass.name}")
                DebugLogger.log("CRASH", "Msg:  ${throwable.message}")
                throwable.stackTrace.take(5).forEach { frame ->
                    DebugLogger.log("CRASH", "  at $frame")
                }
                throwable.cause?.let { cause ->
                    DebugLogger.log("CRASH", "Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(3).forEach { frame ->
                        DebugLogger.log("CRASH", "  at $frame")
                    }
                }
            } catch (_: Exception) {}

            // "Another handler is already registered" is a MediaPipe process-level bug.
            // With our new GGUF-only architecture this should never occur. If it does
            // (e.g. old binary still resident), restart via MainActivity rather than
            // killing the process, so the user sees an error instead of a blank crash.
            val msg = throwable.message.orEmpty()
            if ("Another handler" in msg || "handler is already" in msg) {
                try {
                    DebugLogger.log("CRASH", "MediaPipe handler conflict intercepted — restarting cleanly")
                    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                 android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    if (intent != null) startActivity(intent)
                } catch (_: Exception) {}
                // Still pass to default so the process restarts
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
