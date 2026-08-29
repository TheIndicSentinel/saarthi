package com.saarthi.core.inference.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the session-reset skip/close-only policy. LiteRT JNI create/close is
 * untested here (no native engine / no Robolectric); the decision that used
 * to JNI-create a Conversation on every drawer tap must not regress.
 */
class SessionResetPolicyTest {

    @Test
    fun `unloaded engine does not close anything`() {
        assertFalse(
            shouldCloseConversationOnSessionReset(
                engineLoaded = false,
                hasActiveConversation = true,
            ),
        )
        assertFalse(
            shouldCloseConversationOnSessionReset(
                engineLoaded = false,
                hasActiveConversation = false,
            ),
        )
    }

    @Test
    fun `loaded engine with no live conversation is a no-op`() {
        // After onDone, generateStream already closed the Conversation.
        // Reset must not JNI-create a replacement.
        assertFalse(
            shouldCloseConversationOnSessionReset(
                engineLoaded = true,
                hasActiveConversation = false,
            ),
        )
    }

    @Test
    fun `loaded engine with a live conversation closes it only`() {
        assertTrue(
            shouldCloseConversationOnSessionReset(
                engineLoaded = true,
                hasActiveConversation = true,
            ),
        )
    }

    @Test
    fun `re-selecting the current chat does not reset the engine`() {
        assertFalse(shouldResetEngineOnSessionSwitch("chat-a", "chat-a"))
    }

    @Test
    fun `switching to a different chat does reset the engine`() {
        assertTrue(shouldResetEngineOnSessionSwitch("chat-a", "chat-b"))
    }
}
