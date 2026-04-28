package com.saarthi.feature.assistant.data

/**
 * Parses special action markers embedded by the model at the end of its responses.
 *
 * Supported markers (always appended after the visible response text):
 *
 *   Memory:
 *     [SAARTHI_MEMORY key="user_name" value="Rahul"]
 *
 *   Reminder — absolute time (HH:MM 24h):
 *     [SAARTHI_REMINDER text="Cook dinner" time="18:00"]
 *
 *   Reminder — relative (minutes from now):
 *     [SAARTHI_REMINDER text="Take medicine" delay_minutes="30"]
 */
object ResponseMarkerParser {

    data class ParseResult(
        val cleanText: String,
        val memories: List<MemoryMarker>,
        val reminders: List<ReminderMarker>,
    )

    data class MemoryMarker(val key: String, val value: String)

    /** Either [time] (HH:MM absolute) or [delayMinutes] (relative) will be set — never both. */
    data class ReminderMarker(
        val text: String,
        val time: String? = null,
        val delayMinutes: Int? = null,
    )

    private val MEMORY_REGEX = Regex(
        """\[SAARTHI_MEMORY\s+key="([^"]+)"\s+value="([^"]+)"\]"""
    )
    private val REMINDER_ABS_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text="([^"]+)"\s+time="([^"]+)"\]"""
    )
    private val REMINDER_REL_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text="([^"]+)"\s+delay_minutes="(\d+)"\]"""
    )

    fun parse(raw: String): ParseResult {
        val memories = MEMORY_REGEX.findAll(raw)
            .map { MemoryMarker(it.groupValues[1], it.groupValues[2]) }
            .toList()

        val reminders = buildList {
            REMINDER_ABS_REGEX.findAll(raw).forEach {
                add(ReminderMarker(text = it.groupValues[1], time = it.groupValues[2]))
            }
            REMINDER_REL_REGEX.findAll(raw).forEach {
                add(ReminderMarker(text = it.groupValues[1], delayMinutes = it.groupValues[2].toIntOrNull()))
            }
        }

        return ParseResult(
            cleanText = stripAll(raw),
            memories = memories,
            reminders = reminders,
        )
    }

    /** Strip complete markers from text shown in the streaming bubble. */
    fun stripForDisplay(raw: String): String = stripAll(raw)

    private fun stripAll(text: String): String = text
        .replace(MEMORY_REGEX, "")
        .replace(REMINDER_ABS_REGEX, "")
        .replace(REMINDER_REL_REGEX, "")
        .replace("<end_of_turn>", "")
        .replace("<eos>", "")
        .replace("<start_of_turn>", "")
        .trim()
}
