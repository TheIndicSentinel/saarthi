package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityQuestionTest {

    @Test
    fun `english identity phrases match`() {
        assertTrue(isIdentityQuestion("who are you"))
        assertTrue(isIdentityQuestion("  What's your name?  "))
        assertTrue(isIdentityQuestion("who is saarthi"))
    }

    @Test
    fun `hindi and marathi native phrases match without lowercasing`() {
        assertTrue(isIdentityQuestion("तुम कौन हो"))
        assertTrue(isIdentityQuestion("तुम्ही कोण"))
    }

    @Test
    fun `romanized hindi matches via lowercase latin list`() {
        assertTrue(isIdentityQuestion("Tum kaun ho"))
    }

    @Test
    fun `ordinary questions and length bounds do not match`() {
        assertFalse(isIdentityQuestion("what is the weather in Pune"))
        assertFalse(isIdentityQuestion("x"))
        assertFalse(isIdentityQuestion("a".repeat(81)))
        assertFalse(isIdentityQuestion(""))
    }
}
