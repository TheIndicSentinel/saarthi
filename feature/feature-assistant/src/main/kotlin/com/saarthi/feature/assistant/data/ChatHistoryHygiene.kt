package com.saarthi.feature.assistant.data

import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole

/**
 * Display-side hygiene for persisted chat history.
 *
 * The user turn is written to the DB *before* generation starts, but the
 * assistant reply is only persisted once it succeeds. So a turn that was
 * killed mid-generation — or that ended in an error / empty / stopped reply
 * the app deliberately never persists — leaves a USER row in the DB with no
 * assistant row after it. Rendering that lone question bubble on restart /
 * session-switch looks broken.
 *
 * This filter is applied only when *loading* history for display; it is
 * non-destructive (the DB row is untouched) and never runs during live
 * streaming, so an in-flight turn is unaffected. The prompt builder has its
 * own equivalent pairing pass ([ChatRepositoryImpl.buildCompleteHistoryPairs]),
 * so filtering here keeps what the user sees consistent with what the model
 * is actually given.
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
}
