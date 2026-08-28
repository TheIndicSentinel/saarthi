package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

/**
 * Single-line time context surfaced to the model so greetings match the
 * actual time of day (was a real bug: "Good morning" at 9 PM). Format
 * deliberately compact so it doesn't dominate the prompt.
 *
 * Example output: "Current local time is 21:14 on Mon, 20 May 2026 — it
 * is evening (use a time-appropriate greeting if you greet the user)."
 */
internal fun buildTimeContext(language: SupportedLanguage): String {
    val now = java.util.Calendar.getInstance()
    val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
    val band = when (hour) {
        in 5..11   -> "morning"
        in 12..16  -> "afternoon"
        in 17..20  -> "evening"
        else       -> "night"
    }
    val timeStr = java.text.SimpleDateFormat("HH:mm 'on' EEE, d MMM yyyy", java.util.Locale.US)
        .format(now.time)
    return "Current local time is $timeStr — it is $band."
}
