package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicOcrMergerTest {

    @Test
    fun `empty latin returns devanagari`() {
        assertEquals("किसान क्रेडिट कार्ड", IndicOcrMerger.merge("", "किसान क्रेडिट कार्ड"))
    }

    @Test
    fun `empty devanagari returns latin`() {
        assertEquals("Consent and penalty", IndicOcrMerger.merge("Consent and penalty", ""))
    }

    @Test
    fun `english-only prefers latin`() {
        val latin = "A data breach can attract a penalty of up to 250 crore rupees."
        val deva = "a t d b" // noisy devanagari pass on English scan
        assertEquals(latin, IndicOcrMerger.merge(latin, deva))
    }

    @Test
    fun `hindi-only prefers devanagari`() {
        val latin = "PM Kisan"
        val deva = "प्रधानमंत्री किसान सम्मान निधि योजना के अंतर्गत लाभार्थियों को मिलता है"
        assertEquals(deva, IndicOcrMerger.merge(latin, deva))
    }

    @Test
    fun `bilingual form merges distinct lines`() {
        val latin = "Application Form\nName of Applicant\nDate of Birth"
        val deva = "आवेदन पत्र\nआवेदक का नाम\nजन्म तिथि"
        val merged = IndicOcrMerger.merge(latin, deva)
        assertTrue(merged.contains("Application Form"))
        assertTrue(merged.contains("आवेदक का नाम"))
    }

    @Test
    fun `mergeDistinctLines skips duplicate lines`() {
        val merged = IndicOcrMerger.mergeDistinctLines(
            "Name\nDate",
            "Name\nनाम",
        )
        assertTrue(merged.contains("Name"))
        assertTrue(merged.contains("नाम"))
        assertEquals(1, merged.lines().count { it.trim() == "Name" })
    }

    @Test
    fun `mergeAll chains ML Kit and Tesseract passes`() {
        val latin = "Application Form"
        val deva = "a b c"
        val tamil = "தமிழ்நாடு விவசாய உதவி"
        val merged = IndicOcrMerger.mergeAll(listOf(latin, deva, tamil))
        assertTrue(merged.contains("Application Form"))
        assertTrue(merged.contains("தமிழ்நாடு"))
    }
}
