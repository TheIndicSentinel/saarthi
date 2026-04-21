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
        // Ensures the notification channel exists before any reminder fires
        reminderManager.createNotificationChannel()
    }
}
