package com.saarthi.app.wisdom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules / cancels the daily wisdom notification.
 *
 * Implementation: a single one-shot AlarmManager alarm fired by
 * [com.saarthi.app.ReminderReceiver]. After the receiver delivers
 * today's wisdom it asks this scheduler to re-arm for tomorrow, so a
 * single live alarm is always parked in the system.
 *
 * Why not WorkManager `PeriodicWorkRequest`? Its minimum 15-minute
 * flex window pushes the daily delivery slot off the 8 AM target by up
 * to ~12 minutes a day, which adds up over a week. AlarmManager with
 * `setExactAndAllowWhileIdle` fires within a minute of 8 AM regardless.
 *
 * Channel: reuses [ReminderManager.CHANNEL_ID] so the user can mute
 * everything under one channel in system settings. The receiver renders
 * the wisdom with the same notification builder as a reminder so the
 * visual style stays consistent.
 */
@Singleton
class WisdomNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val ACTION_DAILY_WISDOM = "com.saarthi.app.DAILY_WISDOM"
        const val NOTIFICATION_ID = 715_001    // deterministic — replaces yesterday's
        private const val PENDING_REQUEST_CODE = 715_000
        private const val HOUR_OF_DAY = 8       // 8 AM local time
        private const val MINUTE = 0
    }

    /** Arm a fresh daily alarm for the next [HOUR_OF_DAY]:[MINUTE]. */
    fun enable() {
        scheduleAt(nextTriggerMs())
        Timber.d("WisdomScheduler: enabled — next at ${java.util.Date(nextTriggerMs())}")
    }

    /** Cancel any pending daily wisdom alarm. Safe to call when nothing is armed. */
    fun disable() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(flags = PendingIntent.FLAG_NO_CREATE))
        // Also cancel via the same intent shape we used to schedule, in case
        // FLAG_NO_CREATE returned null because the system has nothing matching:
        am.cancel(buildPendingIntent(flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        Timber.d("WisdomScheduler: disabled — pending alarm cancelled")
    }

    /**
     * Re-arm for tomorrow's slot — called from the receiver after it has
     * posted today's notification. Keeps the "always one alarm parked"
     * invariant without needing a periodic worker.
     */
    fun rearmAfterFire() {
        scheduleAt(tomorrowAtTargetMs())
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun nextTriggerMs(): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
            set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    private fun tomorrowAtTargetMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
            set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    private fun scheduleAt(triggerMs: Long) {
        val pi = buildPendingIntent(
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    // Android 12+ requires SCHEDULE_EXACT_ALARM to use setExact*.
                    // We declare the permission and fall back to inexact if the
                    // user revoked it from system settings (very rare).
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    }
                }
                else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
        }.onFailure { Timber.e(it, "WisdomScheduler: scheduleAt failed") }
    }

    /**
     * Builds the broadcast PendingIntent. `flags = FLAG_NO_CREATE` is used
     * when checking for / cancelling an existing alarm; otherwise pass
     * `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` to (re-)schedule.
     */
    private fun buildPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(ACTION_DAILY_WISDOM).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(context, PENDING_REQUEST_CODE, intent, flags)
    }
}
