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
            putExtra(EXTRA_TITLE, emojiTitleFor(text))
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

    private fun emojiTitleFor(text: String): String {
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
        return "$emoji Saarthi Reminder"
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
