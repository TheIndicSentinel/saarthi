package com.saarthi.feature.assistant.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.inference.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager,
) {
    companion object {
        const val CHANNEL_ID = "saarthi_reminders"   // id kept stable to avoid orphaning
        const val CHANNEL_NAME = "Saarthi"           // carries daily wisdom now (reminders removed)
        const val EXTRA_TITLE = "reminder_title"   // legacy; title is now built at fire time
        const val EXTRA_EMOJI = "reminder_emoji"   // language-independent category emoji
        const val EXTRA_LANG  = "reminder_lang"    // language CODE selected when the reminder was created
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
                description = "Daily wisdom and notifications from Saarthi"
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
    /** Schedule a reminder [delayMinutes] minutes from now. Always accurate — no time-zone ambiguity. */
    fun scheduleByDelay(text: String, delayMinutes: Int): Boolean {
        val triggerMs = System.currentTimeMillis() + delayMinutes * 60_000L
        return scheduleAt(text, triggerMs)
    }

    /** Schedule a reminder at an absolute HH:MM time today (tomorrow if already past). */
    fun scheduleReminder(text: String, timeStr: String): Boolean {
        val triggerMs = parseTimeToMs(timeStr) ?: run {
            Timber.w("ReminderManager: could not parse time '$timeStr'")
            return false
        }
        return scheduleAt(text, triggerMs)
    }

    private fun scheduleAt(text: String, triggerMs: Long): Boolean {
        val id = (text.hashCode().toLong() + triggerMs).toInt() and 0x7FFFFFFF

        val intent = Intent(ACTION_REMINDER).apply {
            setPackage(context.packageName)
            // Capture the language the user had selected WHEN they created the
            // reminder (the app is in the foreground here, so this read is
            // reliable — unlike a cold BroadcastReceiver at fire time). The
            // notification title is localized to THIS language at fire time so
            // it matches the reminder text the model produced in that language.
            putExtra(EXTRA_LANG, languageManager.selectedLanguage.value.code)
            putExtra(EXTRA_EMOJI, emojiFor(text))
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = canScheduleExact(am)
        return try {
            // Industry-standard scheduling for a NOTIFICATION reminder — the same
            // mechanism Google Keep / Tasks / Samsung Reminder use. An exact,
            // Doze-exempt alarm silently wakes the app at the requested time to
            // post a Saarthi notification (see ReminderReceiver). It is invisible:
            // no status-bar alarm icon, no sound, no system "next alarm" entry —
            // that behaviour belongs to setAlarmClock(), which we deliberately do
            // NOT use.
            //
            // setExactAndAllowWhileIdle needs the "Alarms & reminders" access on
            // Android 12+ — granted by default on API ≤ 32, and requested once
            // after onboarding on API 33+ (see MainActivity). When the user has
            // not granted it we fall back to an inexact alarm so the reminder
            // still fires (best-effort) rather than failing; exact is what makes
            // "in 1 minute" land on time even under aggressive OEM battery
            // management (Samsung One UI, MIUI), which was the reported bug.
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            val mins = ((triggerMs - System.currentTimeMillis()) / 60_000.0)
            // File-visible (DebugLogger) so reminder behaviour shows up in the
            // shared debug log alongside [PROMPT]/[CHAT] — Timber alone only
            // reaches logcat, which is why earlier logs had zero reminder data.
            DebugLogger.log(
                "REMINDER",
                "scheduled id=$id exact=$exact in=${"%.1f".format(mins)}min at=${java.util.Date(triggerMs)} text=\"${text.take(40)}\"",
            )
            Timber.d("ReminderManager: scheduled id=$id text='$text' at ${java.util.Date(triggerMs)} exact=$exact")
            true
        } catch (e: Exception) {
            DebugLogger.log("REMINDER", "schedule FAILED id=$id exact=$exact err=${e.message}")
            Timber.e(e, "ReminderManager: failed to schedule")
            false
        }
    }

    /** Whether the app may schedule exact alarms (always true below Android 12). */
    private fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** Category emoji for a reminder subject — language-independent (matches
     *  native keywords across all 10 Indian languages). The localized "Saarthi
     *  Reminder" title is appended at fire time in the current language. */
    private fun emojiFor(text: String): String {
        // text may be in any of the 10 supported Indian languages — match native keywords
        val t = text.lowercase()
        val emoji = when {
            // 🍽️ Food / cooking
            // EN, HI, MR | TA | TE | BN | KN | GU | PA | OR
            t.containsAny(
                "cook", "eat", "food", "meal", "lunch", "dinner", "breakfast", "snack",
                "khana", "bhojan", "jevan", "swayampak",           // HI/MR
                "saappaadu", "samayal", "சாப்பாடு", "சமையல்",     // TA
                "vanta", "tinadam", "వంట", "తినడం",               // TE
                "khawa", "ranna", "খাওয়া", "রান্না",              // BN
                "oota", "aduge", "ಊಟ", "ಅಡುಗೆ",                   // KN
                "khavu", "rasoi", "ખાવું", "રસોઈ",                // GU
                "ਖਾਣਾ", "ਰਸੋਈ",                                   // PA
                "khaiba", "randhiba", "ଖାଇବା", "ରାନ୍ଧିବା",       // OR
            ) -> "🍽️"

            // 💊 Medicine
            t.containsAny(
                "medicine", "tablet", "pill", "dose", "medication",
                "dawa", "dawai", "aushadh", "دوا",                 // HI/MR/PA
                "marunthu", "மருந்து",                             // TA
                "mandu", "మందు",                                   // TE
                "oshudh", "ওষুধ",                                  // BN
                "ಔಷಧ",                                             // KN
                "dava", "દવા",                                     // GU
                "ਦਵਾਈ",                                            // PA
                "ଔଷଧ",                                             // OR
            ) -> "💊"

            // 🏃 Exercise / yoga
            t.containsAny(
                "exercise", "workout", "gym", "yoga", "run", "walk",
                "vyayam", "vyayama", "व्यायाम",                    // HI/MR/KN
                "udarpayrchi", "உடற்பயிற்சி",                     // TA
                "vyayamam", "వ్యాయామం",                           // TE
                "byayam", "ব্যায়াম",                              // BN
                "ವ್ಯಾಯಾಮ",                                         // KN
                "kasarat", "કસરત",                                 // GU
                "ਕਸਰਤ",                                            // PA
                "ବ୍ୟାୟାମ",                                         // OR
            ) -> "🏃"

            // 📅 Meeting / appointment
            t.containsAny(
                "meeting", "call", "appointment", "interview",
                "baithak", "बैठक",                                 // HI/MR
                "santhippu", "சந்திப்பு",                         // TA
                "samavesham", "సమావేశం",                           // TE
                "sabha", "সভা",                                    // BN
                "ಸಭೆ",                                             // KN
                "miting", "મીટિંગ",                                // GU
                "ਮੀਟਿੰਗ",                                          // PA
                "ସଭା",                                             // OR
            ) -> "📅"

            // 😴 Sleep / rest
            t.containsAny(
                "sleep", "rest", "nap",
                "so jao", "neend", "jhop", "ঘুম",                  // HI/MR/BN
                "thookkam", "தூக்கம்",                             // TA
                "nidra", "నిద్ర",                                  // TE
                "nidde", "ನಿದ್ದೆ",                                 // KN
                "oongh", "ઊंઘ",                                    // GU
                "ਨੀਂਦ",                                            // PA
                "shoiba", "ଶୋଇବା",                                 // OR
            ) -> "😴"

            // 💧 Water / hydration
            t.containsAny(
                "water", "drink", "hydrat",
                "paani", "pani", "পানি", "পাণি",                   // HI/MR/BN/PA
                "thanneer", "தண்ணீர்",                             // TA
                "neellu", "నీళ్ళు",                                // TE
                "neeru", "ನೀರು",                                   // KN
                "ਪਾਣੀ",                                            // PA
                "ପାଣି",                                            // OR
            ) -> "💧"

            // 📚 Study / homework
            t.containsAny(
                "study", "read", "homework",
                "padhai", "padhna", "abhyas",                      // HI/MR
                "padippu", "படிப்பு",                              // TA
                "chaduvu", "చదువు",                                // TE
                "porashona", "পড়াশোনা",                           // BN
                "abhyasa", "ಅಭ್ಯಾಸ",                              // KN
                "ਪੜ੍ਹਾਈ",                                          // PA
                "padhiba", "ପଢ଼ିବା",                               // OR
            ) -> "📚"

            // 💳 Payment / bill
            t.containsAny(
                "pay", "bill", "emi", "payment",
                "bhugtan", "paise", "paisa",                       // HI/MR
                "panam", "பணம்",                                   // TA
                "chellimpu", "చెల్లింపు",                          // TE
                "taka", "টাকা",                                    // BN
                "pavati", "ಪಾವತಿ",                                 // KN
                "chukavani", "ચૂકવણી",                             // GU
                "ਭੁਗਤਾਨ",                                          // PA
                "tanka", "ଟଙ୍କା",                                  // OR
            ) -> "💳"

            // 🎂 Birthday / anniversary
            t.containsAny(
                "birthday", "anniversary",
                "janamdin", "janmadin", "vadhdiyas",               // HI/BN/MR
                "pirantha naal", "பிறந்தநாள்",                    // TA
                "puttinaroju", "పుట్టినరోజు",                     // TE
                "জন্মদিন",                                         // BN
                "huttuhabbba", "ಹುಟ್ಟುಹಬ್ಬ",                     // KN
                "janmadiyas", "જન્મદિવસ",                          // GU
                "ਜਨਮਦਿਨ",                                          // PA
                "ଜନ୍ମଦିନ",                                         // OR
            ) -> "🎂"

            else -> "🔔"
        }
        return emoji
    }

    private fun String.containsAny(vararg keywords: String) = keywords.any { this.contains(it) }

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
