package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceChatMessagesTest {

    @Test
    fun `initializing message uses loading body`() {
        val msg = SupportedLanguage.ENGLISH.chatInferenceNotReadyMessage(
            initializing = true,
            reloadingAfterRelease = false,
            modelNotReady = "fallback",
        )
        assertTrue(msg.contains(SupportedLanguage.ENGLISH.loadingModelBody))
        assertTrue(msg.startsWith("⏳"))
    }

    @Test
    fun `reloading message uses reload banner`() {
        val msg = SupportedLanguage.ENGLISH.chatInferenceNotReadyMessage(
            initializing = false,
            reloadingAfterRelease = true,
            modelNotReady = "fallback",
        )
        assertTrue(msg.contains(SupportedLanguage.ENGLISH.reloadingModelBanner))
    }

    @Test
    fun `not ready uses fallback`() {
        assertEquals(
            "fallback",
            SupportedLanguage.ENGLISH.chatInferenceNotReadyMessage(
                initializing = false,
                reloadingAfterRelease = false,
                modelNotReady = "fallback",
            ),
        )
    }
}
