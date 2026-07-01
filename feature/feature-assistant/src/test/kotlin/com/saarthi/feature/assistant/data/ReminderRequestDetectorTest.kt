package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminder gate dropped legitimate requests in the field (device logs:
 * `Dropping 1 reminder marker(s) — user did not request one
 * (msg="Give me a reminder after 1 min …")`), so the model scheduled nothing
 * and the user saw "reminders sometimes don't work". These tests lock in that
 * the broadened detector accepts the real phrasings it used to drop, while
 * still rejecting casual chatter.
 */
class ReminderRequestDetectorTest {

    @Test
    fun `accepts phrasings the old narrow list dropped`() {
        // The exact messages from the device logs that were wrongly dropped.
        val real = listOf(
            "Give me a reminder after 30 seconds for coding practice",
            "Give me a reminder after 1 min for coding practice",
            "give me a reminder",
            "a reminder after 1 hour to drink water",
            "remind me to call mom at 6pm",
            "can you remind me about the meeting",
            "set a reminder for my medicine",
            "set an alarm for 7am",
            "alert me in 10 minutes",
            "notify me when it's 5pm",
            "wake me up at 6",
        )
        real.forEach { msg ->
            assertTrue("Should detect a reminder request in: \"$msg\"", ReminderRequestDetector.wasRequested(msg))
        }
    }

    @Test
    fun `accepts Indian-language reminder phrases`() {
        val real = listOf(
            "mujhe 10 minute me yaad dila dena",   // Hindi (Latin)
            "मुझे शाम को याद दिला देना",            // Hindi (Devanagari)
            "ek reminder set karo",                 // "reminder" substring
        )
        real.forEach { msg ->
            assertTrue("Should detect: \"$msg\"", ReminderRequestDetector.wasRequested(msg))
        }
    }

    @Test
    fun `accepts native-script reminder phrases across supported languages`() {
        val real = listOf(
            "నాకు 5 నిమిషాల్లో గుర్తు చేయి",     // Telugu
            "எனக்கு நினைவூட்டு",                  // Tamil
            "আমাকে মনে করিয়ে দাও",                // Bengali
            "मला संध्याकाळी आठवण कर",             // Marathi
            "ನನಗೆ ನೆನಪಿಸು",                       // Kannada
            "મને યાદ કરાવ",                        // Gujarati
            "ਮੈਨੂੰ ਯਾਦ ਕਰਾ ਦਿਓ",                  // Punjabi
            "ମୋତେ ମନେ ପକାଅ",                       // Odia
        )
        real.forEach { msg ->
            assertTrue("Should detect: \"$msg\"", ReminderRequestDetector.wasRequested(msg))
        }
    }

    @Test
    fun `rejects casual messages with no reminder intent`() {
        // These must NOT schedule even if the model over-emits a marker.
        val casual = listOf(
            "Tell me about crop rotation",
            "What is the capital of India?",
            "Explain photosynthesis simply",
            "How do I cook dal?",
            "Thanks, that was helpful",
        )
        casual.forEach { msg ->
            assertFalse("Should NOT detect a reminder in: \"$msg\"", ReminderRequestDetector.wasRequested(msg))
        }
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(ReminderRequestDetector.wasRequested("REMIND ME to pay the bill"))
        assertTrue(ReminderRequestDetector.wasRequested("Set An Alarm For 8"))
    }
}