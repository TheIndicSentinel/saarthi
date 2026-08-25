package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicOcrPolicyTest {

    @Test
    fun `empty ML Kit output always escalates to Tesseract`() {
        assertTrue(IndicOcrPolicy.needsRegionalTesseractPass("", SupportedLanguage.TAMIL))
    }

    @Test
    fun `Tamil UI skips Tesseract when Tamil script is present`() {
        val text = "தமிழ்நாடு விவசாயிக்கு உதவித்தொகை வழங்கப்படுகிறது"
        assertFalse(IndicOcrPolicy.needsRegionalTesseractPass(text, SupportedLanguage.TAMIL))
    }

    @Test
    fun `Tamil UI escalates when ML Kit returned only Latin noise`() {
        assertTrue(
            IndicOcrPolicy.needsRegionalTesseractPass(
                "Application Form Name Date",
                SupportedLanguage.TAMIL,
            ),
        )
    }

    @Test
    fun `English UI escalates when no Indic script recovered`() {
        assertTrue(
            IndicOcrPolicy.needsRegionalTesseractPass(
                "Scanned gibberish xkjdhf lskdjf more latin noise",
                SupportedLanguage.ENGLISH,
            ),
        )
    }

    @Test
    fun `English UI skips when Bengali script already present`() {
        val text = "আবেদনপত্র নাম তারিখ"
        assertFalse(IndicOcrPolicy.needsRegionalTesseractPass(text, SupportedLanguage.ENGLISH))
    }

    @Test
    fun `Hindi UI skips when Devanagari is present`() {
        val text = "किसान क्रेडिट कार्ड योजना के अंतर्गत"
        assertFalse(IndicOcrPolicy.needsRegionalTesseractPass(text, SupportedLanguage.HINDI))
    }

    @Test
    fun `tesseractLanguages uses user language code when regional`() {
        assertEquals("ben", IndicOcrPolicy.tesseractLanguages(SupportedLanguage.BENGALI))
        assertEquals("tam", IndicOcrPolicy.tesseractLanguages(SupportedLanguage.TAMIL))
    }

    @Test
    fun `tesseractLanguages falls back to combined pack for English UI`() {
        assertEquals(
            IndicOcrPolicy.COMBINED_REGIONAL_LANGS,
            IndicOcrPolicy.tesseractLanguages(SupportedLanguage.ENGLISH),
        )
    }
}
