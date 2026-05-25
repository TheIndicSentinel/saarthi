package com.saarthi.app

import android.app.Application
import com.saarthi.app.wisdom.WisdomNotificationScheduler
import com.saarthi.core.i18n.WisdomNotificationPreference
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.InferenceService
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class SaarthiApp : Application() {

    @Inject lateinit var reminderManager: ReminderManager
    @Inject lateinit var crashReporter: com.saarthi.core.common.CrashReporter
    @Inject lateinit var wisdomPreference: WisdomNotificationPreference
    @Inject lateinit var wisdomScheduler: WisdomNotificationScheduler
    @Inject lateinit var kisanPackInstaller: com.saarthi.feature.assistant.data.KisanPackInstaller
    @Inject lateinit var packUpdateScheduler: com.saarthi.app.packs.PackUpdateScheduler

    // Eagerly construct the chat repository at app start so its Room queries
    // (default session + last conversation) run in parallel with model init,
    // not on the user's first chat tap. ChatRepositoryImpl is @Singleton, so
    // referencing it here just wakes up its init block once.
    @Inject lateinit var chatRepositoryWarmup: com.saarthi.feature.assistant.domain.ChatRepository

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
        // Re-arm the daily wisdom alarm if the user has it on. Idempotent:
        // setExactAndAllowWhileIdle with the same request code just replaces
        // the existing pending intent, so repeated launches are safe.
        appScope.launch {
            if (wisdomPreference.enabled.first()) wisdomScheduler.enable()
        }
        // First-launch seed install of the Kisan pack so the persona is
        // useful out-of-the-box. Idempotent — bails immediately if any
        // version is already installed. The seed lives in assets so the
        // feature works with zero network.
        appScope.launch {
            runCatching { kisanPackInstaller.installSeedIfAbsent() }
        }
        // Periodic Kisan-pack update poll. Idempotent (KEEP policy);
        // no-op when no manifest URL is configured via BuildConfig, so
        // it's safe to enqueue unconditionally.
        runCatching { packUpdateScheduler.schedule() }
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // ── JVM-crash signal for the inference engine ─────────────────
            // The engine's crash-recovery layer (LiteRTInferenceEngine) looks at
            // SharedPreferences flags on the next launch to decide whether the
            // crash was a native SIGKILL (GPU/NPU fault) or something else.
            // If we just died from a Kotlin Throwable, the engine had nothing
            // to do with it — stamp this flag so the recovery layer skips the
            // GPU ban entirely. Without it, an unrelated JVM bug bans the GPU
            // for 24h and the user wonders why their fast backend disappeared.
            runCatching {
                getSharedPreferences("litert_engine_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("saarthi_last_crash_was_jvm", true)
                    .putString("saarthi_last_crash_class", throwable.javaClass.name)
                    .commit()
            }
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
                // Route into the CrashReporter abstraction too — currently the
                // LocalCrashReporter writes to the same on-device debug log, but
                // once Firebase Crashlytics is bound in DI this becomes the
                // single line of code that ships crashes off-device.
                crashReporter.recordException(
                    throwable,
                    mapOf(
                        "thread" to thread.name,
                        "appVersion" to runCatching {
                            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                        }.getOrDefault(""),
                    ),
                )
            } catch (_: Exception) {}

            // "Another handler is already registered" is a MediaPipe process-level bug
            // we still observe occasionally on stale native state. Restart via MainActivity
            // rather than killing the process so the user sees an error instead of a blank crash.
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
