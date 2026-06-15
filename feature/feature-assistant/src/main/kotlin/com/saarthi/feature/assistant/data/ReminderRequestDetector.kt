package com.saarthi.feature.assistant.data

/**
 * Decides whether a user's message is actually asking for a reminder / alarm.
 *
 * This is the runtime gate applied BEFORE scheduling: the on-device model
 * (Gemma) over-emits `[SAARTHI_REMINDER]` markers — it has been seen to attach
 * one to casual topics ("GGUF optimization") — so we only schedule when the
 * user's own words show reminder intent. The model emitting a marker is
 * necessary but not sufficient.
 *
 * Balance: the earlier hand-listed phrase set was too narrow and silently
 * DROPPED legitimate requests like "give me a reminder after 1 min" (device
 * logs: `Dropping 1 reminder marker(s) — user did not request one`), which is
 * the top cause of "reminders sometimes don't work". For a reminder *feature*,
 * a missed real request is worse than a rare spurious one (which still also
 * requires the model to have emitted a marker). So detection keys off the
 * substring "remind", which covers EVERY remind*/reminder* phrasing at once
 * ("remind me", "give me a reminder", "reminder after", "reminded"…), plus the
 * common alarm/alert vocabulary and the main Indian-language equivalents.
 *
 * Pure and dependency-free so the gate is unit-testable without standing up the
 * whole chat repository.
 */
object ReminderRequestDetector {

    /** True when [userMessage] shows an explicit reminder / alarm intent. */
    fun wasRequested(userMessage: String): Boolean {
        val msg = userMessage.lowercase()
        return TRIGGERS.any { msg.contains(it) }
    }

    private val TRIGGERS: List<String> = listOf(
        // English — "remind" catches remind me / remind us / give me a reminder /
        // reminder after / set a reminder / reminded … (every remind*/reminder*).
        "remind",
        // Alarm / alert / notify / wake phrasings.
        "alarm", "alert me", "notify me", "ping me", "wake me",
        // Natural-language implicit asks.
        "don't let me forget", "dont let me forget",
        "don't let me miss", "dont let me miss",
        "make sure i remember", "help me remember",
        // Contextual — user states a scheduled event + time (implicit ask).
        "i have a meeting at", "my meeting is at",
        "my appointment is at", "my appointment at",
        "my class is at", "my class starts at",
        // Hindi (Latin + Devanagari)
        "yaad dila", "yaad rakh", "yaad dilana", "yaad dilao", "yaad karwa",
        "mujhe yaad kara",
        "याद दिला", "याद रख", "रिमाइंडर", "अलार्म",
        // Tamil / Telugu / Bengali / Marathi / Kannada / Gujarati / Punjabi / Odia
        "ninaivu padut", "gnabakam unchu", "mone koriye dao",
        "athavan karun", "nenapu ittuko", "yaad rakhjo",
    )
}