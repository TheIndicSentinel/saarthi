package com.saarthi.feature.assistant.data

/**
 * Parses special action markers that the model embeds in its responses.
 *
 * The model is instructed (via system prompt) to append these at the end of its
 * message when it detects the user wants to save a memory or set a reminder:
 *
 *   [SAARTHI_MEMORY key="user_name" value="Rahul"]
 *   [SAARTHI_REMINDER text="Cook sandwich" time="18:00"]
 *
 * These tags are stripped from the visible response and processed by the app.
 */
object ResponseMarkerParser {

    data class ParseResult(
        val cleanText: String,
        val memories: List<MemoryMarker>,
        val reminders: List<ReminderMarker>,
    )

    data class MemoryMarker(val key: String, val value: String)
    data class ReminderMarker(val text: String, val time: String)

    private val MEMORY_REGEX   = Regex("""\[SAARTHI_MEMORY\s+key="([^"]+)"\s+value="([^"]+)"\]""")
    private val REMINDER_REGEX = Regex("""\[SAARTHI_REMINDER\s+text="([^"]+)"\s+time="([^"]+)"\]""")

    fun parse(raw: String): ParseResult {
        val memories  = MEMORY_REGEX.findAll(raw).map   { MemoryMarker(it.groupValues[1], it.groupValues[2]) }.toList()
        val reminders = REMINDER_REGEX.findAll(raw).map { ReminderMarker(it.groupValues[1], it.groupValues[2]) }.toList()

        // Remove all markers from the displayed text
        val clean = raw
            .replace(MEMORY_REGEX,   "")
            .replace(REMINDER_REGEX, "")
            .trimEnd()

        return ParseResult(cleanText = clean, memories = memories, reminders = reminders)
    }
}
