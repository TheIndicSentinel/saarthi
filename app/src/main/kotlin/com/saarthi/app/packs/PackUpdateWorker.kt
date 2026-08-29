package com.saarthi.app.packs

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saarthi.app.R
import com.saarthi.core.inference.DebugLogger
import com.saarthi.feature.assistant.data.PackUpdateChecker
import com.saarthi.feature.assistant.data.PackUpdateOutcome
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that polls the Kisan-pack manifest via [PackUpdateChecker]
 * (same verify-then-install path as the in-app refresh tap) and posts a
 * "pack updated" notification on a genuine later refresh.
 *
 * Schedule + constraints: see [PackUpdateScheduler].
 */
class PackUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun packUpdateChecker(): PackUpdateChecker
        fun languageManager(): com.saarthi.core.i18n.LanguageManager
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val checker = deps.packUpdateChecker()
        // Belt-and-suspenders: [PackUpdateScheduler] no longer enqueues when
        // the manifest URL is empty, but a leftover unique work from an older
        // build can still fire once. Do not hit the network for a no-op URL.
        if (!checker.isRemoteConfigured) {
            DebugLogger.log("PACK", "PackUpdateWorker skipped — KISAN_PACK_MANIFEST_URL is empty")
            return@withContext Result.success()
        }
        when (val outcome = checker.checkAndInstall()) {
            PackUpdateOutcome.NetworkFailed -> Result.retry()
            is PackUpdateOutcome.Updated -> {
                if (outcome.fromVersion > 0) {
                    notifyPackUpdated(deps.languageManager().awaitSelectedLanguage())
                } else {
                    DebugLogger.log("PACK", "First pack install (seed) — suppressing update notification")
                }
                Result.success()
            }
            else -> Result.success()
        }
    }

    private fun notifyPackUpdated(lang: com.saarthi.core.i18n.SupportedLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val tapIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val tapPi = PendingIntent.getActivity(
            applicationContext, NOTIF_ID, tapIntent ?: android.content.Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🌾 ${lang.packUpdatedTitle}")
            .setContentText(lang.packUpdatedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lang.packUpdatedBody))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, notification)
    }

    companion object {
        const val NOTIF_ID = 815_001
    }
}
