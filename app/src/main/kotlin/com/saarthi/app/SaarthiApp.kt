package com.saarthi.app

import android.app.Application
import com.saarthi.core.inference.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class SaarthiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        DebugLogger.init(this)
    }
}
