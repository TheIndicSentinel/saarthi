package com.saarthi.feature.assistant.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "saarthi_reminders"
        const val CHANNEL_NAME = "Saarthi Reminders"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_TEXT  = "reminder_text"
        const val EXTRA_ID    = "reminder_id"
        const val ACTION_REMINDER = "com.saarthi.app.REMINDER"
    }

    init {
        createNotificationChannel()
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Scheduled reminders from Saarthi"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Schedule a local notification.
     * @param text    The reminder message (shown in notification body)
     * @param timeStr Time in "HH:MM" 24h format (today; tomorrow if time already passed)
     * @return true if scheduled successfully
     */
    fun scheduleReminder(text: String, timeStr: String): Boolean {
        val triggerMs = parseTimeToMs(timeStr) ?: run {
            Timber.w("ReminderManager: could not parse time '$timeStr'")
            return false
        }
        val id = (text.hashCode().toLong() + triggerMs).toInt() and 0x7FFFFFFF

        val intent = Intent(context, Class.forName("com.saarthi.app.ReminderReceiver")).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TITLE, "Saarthi Reminder")
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    } else {
                        // Fallback: inexact (fires within ~15 min window)
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    }
                }
                else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            Timber.d("ReminderManager: scheduled id=$id text='$text' at ${java.util.Date(triggerMs)}")
            true
        } catch (e: Exception) {
            Timber.e(e, "ReminderManager: failed to schedule")
            false
        }
    }

    private fun parseTimeToMs(timeStr: String): Long? {
        return try {
            val parts = timeStr.trim().split(":").map { it.trim().toInt() }
            if (parts.size < 2) return null
            val hour = parts[0].coerceIn(0, 23)
            val minute = parts[1].coerceIn(0, 59)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // If the time is already passed today, schedule for tomorrow
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
