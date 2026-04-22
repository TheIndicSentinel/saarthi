package com.saarthi.app

import android.app.Application
import com.saarthi.core.inference.DebugLogger
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
                // Log the first 5 stack frames
                throwable.stackTrace.take(5).forEach { frame ->
                    DebugLogger.log("CRASH", "  at $frame")
                }
                // Log cause if present
                throwable.cause?.let { cause ->
                    DebugLogger.log("CRASH", "Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(3).forEach { frame ->
                        DebugLogger.log("CRASH", "  at $frame")
                    }
                }
            } catch (_: Exception) {
                // Never let the crash logger itself crash
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
