package com.saarthi.core.common.di

import com.saarthi.core.common.CrashReporter
import com.saarthi.core.common.LocalCrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the default [LocalCrashReporter]. Swap to a Firebase-backed
 * implementation once `google-services.json` is in place (see
 * [CrashReporter] for the migration recipe).
 */
@Module
@InstallIn(SingletonComponent::class)
object CrashReporterModule {
    @Provides
    @Singleton
    fun provideCrashReporter(): CrashReporter =
        // Prefer Crashlytics when Firebase is on the classpath + initialised
        // (i.e. google-services.json was bundled at build time). Falls back to
        // on-device DebugLogger writes otherwise — keeping the offline-first
        // promise for self-built / unconfigured installs.
        com.saarthi.core.common.CrashlyticsCrashReporter.tryCreate() ?: LocalCrashReporter()
}
