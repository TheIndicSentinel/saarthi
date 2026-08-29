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
    fun `Hindi UI escalates when ML Kit returned long Latin noise`() {
        assertTrue(
            IndicOcrPolicy.needsRegionalTesseractPass(
                "Application Form Name Date Address Signature Photo Page One Two",
                SupportedLanguage.HINDI,
            ),
        )
    }

    @Test
    fun `detectRegionalTesseractCodes finds Devanagari hin pack`() {
        val hindi = "किसान क्रेडिट कार्ड योजना के अंतर्गत लाभ"
        assertEquals(listOf("hin"), IndicOcrPolicy.detectRegionalTesseractCodes(hindi))
    }

    @Test
    fun `tesseractLanguages uses detected document script not UI language`() {
        val bengali = "আবেদনপত্র নাম তারিখ দিন"
        assertEquals("ben", IndicOcrPolicy.tesseractLanguages(SupportedLanguage.TAMIL, bengali))
        assertEquals("ben", IndicOcrPolicy.tesseractLanguages(SupportedLanguage.ENGLISH, bengali))
        assertEquals(
            listOf("ben"),
            IndicOcrPolicy.detectRegionalTesseractCodes(bengali),
        )
    }

    @Test
    fun `unknown script prefers UI language then the combined pack`() {
        val unknown = IndicOcrPolicy.tesseractLanguages(SupportedLanguage.TAMIL, "Application Form")
        assertTrue(unknown.startsWith("tam+"))
        assertTrue(unknown.contains("ben"))
        assertEquals(
            IndicOcrPolicy.COMBINED_REGIONAL_LANGS,
            IndicOcrPolicy.tesseractLanguages(SupportedLanguage.ENGLISH, ""),
        )
    }

    @Test
    fun `truncated tessdata is not plausible`() {
        assertFalse(isPlausibleTessdataSize("tam.traineddata", 50_000L))
        assertTrue(isPlausibleTessdataSize("tam.traineddata", 2_600_000L))
        assertFalse(isPlausibleTessdataSize("hin.traineddata", 50_000L))
        assertTrue(isPlausibleTessdataSize("hin.traineddata", 1_000_000L))
    }
}
