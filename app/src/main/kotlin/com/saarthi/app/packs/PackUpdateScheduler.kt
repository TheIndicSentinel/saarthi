package com.saarthi.app.packs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.saarthi.app.BuildConfig
import com.saarthi.core.inference.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the periodic [PackUpdateWorker]. Called once from
 * SaarthiApp.onCreate — `ExistingPeriodicWorkPolicy.KEEP` makes
 * repeated calls idempotent so an unintended re-enqueue won't double
 * the worker.
 *
 * Constraints chosen for "respectful background data refresh":
 *  • Any network (Wi-Fi or mobile data) — see [schedule]'s own comment for
 *    why UNMETERED-only would be wrong here. The tiny pack size keeps
 *    cellular impact negligible either way.
 *  • Battery not low — won't run when the user is conserving power.
 *  • Periodic, 24-hour interval — a pack updates daily at most.
 *
 * No-ops when [BuildConfig.KISAN_PACK_MANIFEST_URL] is empty: we still
 * enqueue the worker (so it auto-activates when a manifest URL ships
 * in a future release) but the worker itself short-circuits to
 * `Result.success()` on every run with an empty URL.
 */
@Singleton
class PackUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule() {
        val constraints = Constraints.Builder()
            // CONNECTED (any network: Wi-Fi or mobile data) per product
            // intent — Saarthi is fully offline at runtime, so the only
            // moments network is touched are explicit model downloads
            // and pack-update polls; gating those on Wi-Fi-only would
            // strand users on mobile-only plans without ever updating.
            // The 24 h cadence + small pack size (a few KB to a couple
            // of MB at most) keep cellular impact negligible.
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PackUpdateWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,    // idempotent across app launches
            request,
        )
        val configured = BuildConfig.KISAN_PACK_MANIFEST_URL.isNotBlank()
        DebugLogger.log(
            "PACK",
            "PackUpdateWorker enqueued (every ${REPEAT_INTERVAL_HOURS}h, any network). manifest=${if (configured) "configured" else "EMPTY (no-op)"}",
        )
    }

    companion object {
        private const val WORK_TAG = "saarthi-kisan-pack-update"
        private const val REPEAT_INTERVAL_HOURS = 24L
    }
}
