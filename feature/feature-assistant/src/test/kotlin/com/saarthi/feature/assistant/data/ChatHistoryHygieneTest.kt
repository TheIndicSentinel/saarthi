package com.saarthi.feature.assistant.data

import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the restart / session-switch display filter: a persisted history that
 * ends in a lone USER row (turn killed mid-generation, or an error/empty reply
 * the app never persisted) must not render that dangling question bubble.
 */
class ChatHistoryHygieneTest {

    private fun user(id: String) = ChatMessage(id = id, content = "q", role = MessageRole.USER)
    private fun assistant(id: String) = ChatMessage(id = id, content = "a", role = MessageRole.ASSISTANT)

    private fun ids(list: List<ChatMessage>) = list.map { it.id }

    @Test
    fun `drops a trailing orphaned user turn`() {
        val history = listOf(user("u1"), assistant("a1"), user("u2"))
        assertEquals(listOf("u1", "a1"), ids(ChatHistoryHygiene.dropOrphanedUserTurns(history)))
    }

    @Test
    fun `keeps complete user to assistant pairs untouched`() {
        val history = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(listOf("u1", "a1", "u2", "a2"), ids(ChatHistoryHygiene.dropOrphanedUserTurns(history)))
    }

    @Test
    fun `drops a user turn followed by another user turn`() {
        val history = listOf(user("u1"), user("u2"), assistant("a2"))
        assertEquals(listOf("u2", "a2"), ids(ChatHistoryHygiene.dropOrphanedUserTurns(history)))
    }

    @Test
    fun `keeps assistant messages and empty input as-is`() {
        assertEquals(emptyList<String>(), ids(ChatHistoryHygiene.dropOrphanedUserTurns(emptyList())))
        val onlyAssistant = listOf(assistant("a1"))
        assertEquals(listOf("a1"), ids(ChatHistoryHygiene.dropOrphanedUserTurns(onlyAssistant)))
    }

    @Test
    fun `prompt pairing keeps complete user to assistant pairs`() {
        val history = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(
            listOf("u1", "a1", "u2", "a2"),
            ids(ChatHistoryHygiene.completeUserAssistantPairs(history)),
        )
    }

    @Test
    fun `prompt pairing drops a trailing orphaned user turn`() {
        val history = listOf(user("u1"), assistant("a1"), user("u2"))
        assertEquals(listOf("u1", "a1"), ids(ChatHistoryHygiene.completeUserAssistantPairs(history)))
    }

    @Test
    fun `prompt pairing drops a lone assistant unlike the display filter`() {
        val history = listOf(assistant("a0"), user("u1"), assistant("a1"))
        assertEquals(listOf("a0", "u1", "a1"), ids(ChatHistoryHygiene.dropOrphanedUserTurns(history)))
        assertEquals(listOf("u1", "a1"), ids(ChatHistoryHygiene.completeUserAssistantPairs(history)))
    }
}
