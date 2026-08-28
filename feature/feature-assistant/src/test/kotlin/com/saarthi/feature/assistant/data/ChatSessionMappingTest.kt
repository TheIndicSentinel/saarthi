package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.ChatSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionMappingTest {

    @Test
    fun `toSession copies session chip fields`() {
        val entity = ChatSessionEntity(
            id = "s1",
            title = "New Chat",
            createdAt = 10L,
            updatedAt = 20L,
        )
        val session = entity.toSession()
        assertEquals("s1", session.id)
        assertEquals("New Chat", session.title)
        assertEquals(10L, session.createdAt)
        assertEquals(20L, session.updatedAt)
    }
}
