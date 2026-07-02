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
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
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
    @Inject lateinit var languageManager: LanguageManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderManager.ACTION_REMINDER -> handleReminder(context, intent)
            WisdomNotificationScheduler.ACTION_DAILY_WISDOM -> handleDailyWisdom(context)
        }
    }

    // ── Per-action handlers ──────────────────────────────────────────────

    private fun handleReminder(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderManager.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return
        val emoji = intent.getStringExtra(ReminderManager.EXTRA_EMOJI) ?: "🔔"
        val id = intent.getIntExtra(ReminderManager.EXTRA_ID, System.currentTimeMillis().toInt())

        // Localize the title to the language the reminder was CREATED in
        // (stored in the alarm intent — reliable even in a cold receiver
        // process, unlike selectedLanguage.value which defaults to HINDI before
        // DataStore loads). The BODY is the specific subject the user asked for,
        // shown verbatim — never a generic "it's time" placeholder.
        val lang = intent.getStringExtra(ReminderManager.EXTRA_LANG)
            ?.let { SupportedLanguage.fromCode(it) }
            ?: languageManager.selectedLanguage.value
        val title = "$emoji ${lang.reminderNotificationTitle}"
        post(context, id = id, title = title, text = text)
    }

    private fun handleDailyWisdom(context: Context) {
        val lang = languageManager.selectedLanguage.value
        val wisdom = DailyWisdomCatalog.forDate()
        post(
            context = context,
            id = WisdomNotificationScheduler.NOTIFICATION_ID,
            title = "🪔 ${lang.wisdomNotificationTitle}",
            text = wisdom.localized(lang),
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
                // File-visible so a "reminder didn't show" report is diagnosable:
                // the alarm DID fire, but the OS suppressed the notification
                // because the user hasn't granted POST_NOTIFICATIONS.
                com.saarthi.core.inference.DebugLogger.log(
                    "REMINDER", "fired id=$id but SUPPRESSED — POST_NOTIFICATIONS not granted",
                )
                return
            }
        }
        com.saarthi.core.inference.DebugLogger.log("REMINDER", "fired id=$id — posting notification")

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
