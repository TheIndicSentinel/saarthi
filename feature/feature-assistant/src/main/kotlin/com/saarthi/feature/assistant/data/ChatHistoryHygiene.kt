package com.saarthi.feature.assistant.data

import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole

/**
 * Chat-history pairing used in two places with **slightly different** rules:
 *
 * 1. [dropOrphanedUserTurns] — display on restart / session-switch. Drops a
 *    USER message that has no following assistant message. Keeps lone
 *    assistant rows (rare, but rendering them is harmless).
 * 2. [completeUserAssistantPairs] — prompt recap / conversation context.
 *    Keeps only complete user→assistant pairs. Lone assistant rows are also
 *    skipped so Gemma never sees consecutive same-role turns (chat template).
 *
 * The user turn is written to the DB *before* generation starts, but the
 * assistant reply is only persisted once it succeeds. A killed or
 * error/empty/stopped turn therefore leaves a USER row with no assistant
 * after it. Both filters drop that orphan. Neither mutates the DB; live
 * streaming does not go through these helpers.
 */
object ChatHistoryHygiene {

    /**
     * Drops orphaned USER turns from a timestamp-ordered [history]: a user
     * message that is last, or is not immediately followed by an assistant
     * message. Complete user→assistant pairs and assistant messages are kept
     * as-is.
     */
    fun dropOrphanedUserTurns(history: List<ChatMessage>): List<ChatMessage> {
        if (history.isEmpty()) return history
        val result = ArrayList<ChatMessage>(history.size)
        for (i in history.indices) {
            val msg = history[i]
            val isOrphanUser = msg.role == MessageRole.USER &&
                (i + 1 >= history.size || history[i + 1].role != MessageRole.ASSISTANT)
            if (!isOrphanUser) result.add(msg)
        }
        return result
    }

    /**
     * Returns only complete user→assistant pairs from [history].
     * Orphaned user messages (no following model response) and lone assistant
     * messages are dropped — they appear after a crash where the assistant
     * never finished, and including them creates consecutive same-role turns
     * that violate Gemma's chat template.
     */
    fun completeUserAssistantPairs(history: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var i = 0
        while (i < history.size) {
            val msg = history[i]
            if (msg.role == MessageRole.USER &&
                i + 1 < history.size &&
                history[i + 1].role == MessageRole.ASSISTANT) {
                result.add(history[i])
                result.add(history[i + 1])
                i += 2
            } else {
                i++  // skip orphaned user message or lone assistant message
            }
        }
        return result
    }
}
