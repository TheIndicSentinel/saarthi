package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3.2 — Indian amount normalization. */
class AmountNormalizationTest {

    @Test
    fun `normalize strips grouping commas`() {
        assertEquals("125", normalizeIndianAmountDigits("1,25"))
        assertEquals("250000", normalizeIndianAmountDigits("2,50,000"))
    }

    @Test
    fun `extract signatures from crore lakh and rs`() {
        val sigs = extractMonetarySignatures("Penalty up to ₹125 crore and Rs 50 lakh fee.")
        assertTrue(sigs.contains("125crore"))
        assertTrue(sigs.contains("50lakh"))
    }

    @Test
    fun `corpus match ignores comma grouping`() {
        val corpus = "Breach — monetary penalty up to ₹1,25 crore"
        assertTrue(corpusContainsMonetarySignature("125crore", corpus))
    }
}
