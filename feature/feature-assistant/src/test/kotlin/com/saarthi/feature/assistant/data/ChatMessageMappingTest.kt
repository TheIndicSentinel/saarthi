package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.ConversationEntity
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageMappingTest {

    @Test
    fun `toEntity copies bubble fields onto the session row`() {
        val msg = ChatMessage(
            id = "m1",
            content = "hello",
            role = MessageRole.USER,
            tokenCount = 3,
            timestamp = 42L,
        )
        val entity = msg.toEntity("sess")
        assertEquals("m1", entity.id)
        assertEquals("hello", entity.content)
        assertEquals("USER", entity.role)
        assertEquals(3, entity.tokenCount)
        assertEquals(42L, entity.timestamp)
        assertEquals("sess", entity.sessionId)
    }

    @Test
    fun `toChatMessage round-trips role via valueOf`() {
        val entity = ConversationEntity(
            id = "a1",
            content = "hi",
            role = "ASSISTANT",
            timestamp = 7L,
            tokenCount = 9,
            sessionId = "s",
        )
        val msg = entity.toChatMessage()
        assertEquals("a1", msg.id)
        assertEquals("hi", msg.content)
        assertEquals(MessageRole.ASSISTANT, msg.role)
        assertEquals(7L, msg.timestamp)
        assertEquals(9, msg.tokenCount)
        assertEquals(false, msg.isStreaming)
    }
}
