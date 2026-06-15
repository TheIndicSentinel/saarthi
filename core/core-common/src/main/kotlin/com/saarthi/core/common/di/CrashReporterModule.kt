package com.saarthi.core.common.di

import com.saarthi.core.common.CrashReporter
import com.saarthi.core.common.LocalCrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [LocalCrashReporter] — crash/diagnostic events go ONLY to the on-device
 * `saarthi_debug.log`, never off the phone. No Firebase / Crashlytics / Analytics:
 * Saarthi sends no telemetry to any server.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrashReporterModule {
    @Provides
    @Singleton
    fun provideCrashReporter(): CrashReporter = LocalCrashReporter()
}
