package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySaveIntentTest {

    @Test
    fun explicit_remember_phrases_match() {
        assertTrue(userRequestedMemorySave("Please remember my name is Arjun"))
        assertTrue(userRequestedMemorySave("mera naam yaad rakh — Arjun"))
        assertTrue(userRequestedMemorySave("याद रखो मेरा शहर पुणे है"))
    }

    @Test
    fun casual_disclosure_does_not_match() {
        assertFalse(userRequestedMemorySave("My name is Arjun"))
        assertFalse(userRequestedMemorySave("मेरा नाम अर्जुन है"))
        assertFalse(userRequestedMemorySave("I am a teacher in Pune"))
    }
}
