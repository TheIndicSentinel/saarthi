package com.saarthi.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.saarthi.app.wisdom.WisdomNotificationScheduler
import com.saarthi.core.i18n.DailyWisdomCatalog
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver for both one-off reminders and the daily wisdom card.
 *
 * Two actions live behind one receiver because they share notification
 * channel, permission check, tap-to-open intent, and overall builder
 * style — duplicating them across two receivers would only spread the
 * permission gate + builder boilerplate.
 *
 *   action = REMINDER       — fired by ReminderManager for a single
 *                              user-requested reminder (delay or HH:MM).
 *   action = DAILY_WISDOM   — fired by WisdomNotificationScheduler each
 *                              day at 8 AM; after posting the wisdom we
 *                              re-arm tomorrow's alarm in the same call.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var wisdomScheduler: WisdomNotificationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderManager.ACTION_REMINDER -> handleReminder(context, intent)
            WisdomNotificationScheduler.ACTION_DAILY_WISDOM -> handleDailyWisdom(context)
        }
    }

    // ── Per-action handlers ──────────────────────────────────────────────

    private fun handleReminder(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderManager.EXTRA_TITLE) ?: "Saarthi Reminder"
        val text  = intent.getStringExtra(ReminderManager.EXTRA_TEXT)  ?: return
        val id    = intent.getIntExtra(ReminderManager.EXTRA_ID, System.currentTimeMillis().toInt())
        post(context, id = id, title = title, text = text)
    }

    private fun handleDailyWisdom(context: Context) {
        val wisdom = DailyWisdomCatalog.forDate()
        post(
            context = context,
            id = WisdomNotificationScheduler.NOTIFICATION_ID,
            title = "🪔 Thought of the day",
            text = "${wisdom.sanskrit}\n${wisdom.english}",
        )
        // Always re-arm — even if posting was blocked by permission, the
        // user may grant POST_NOTIFICATIONS later and we want the alarm
        // chain to keep flowing without requiring an app re-launch.
        wisdomScheduler.rearmAfterFire()
    }

    // ── Shared notification builder ──────────────────────────────────────

    private fun post(context: Context, id: Int, title: String, text: String) {
        // POST_NOTIFICATIONS gate (Android 13+). Without this we silently
        // throw on `notify()`.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        // Tap → open the app, preferring an existing task.
        val tapIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val tapPi = PendingIntent.getActivity(
            context, id, tapIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
