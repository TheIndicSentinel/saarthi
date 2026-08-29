package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase A1 — fuzzy heading match requires a distinctive token overlap. */
class PhaseAHeadingMatchTest {

    private val eduHeadings = listOf(
        "The Sun is the primary source of energy for Earth's climate system",
        "Section IV How is Weather different from Climate",
        "The ocean exerts a major control on climate",
    )

    @Test
    fun `generic earth climate overlap does not fuzzy match sun heading`() {
        assertNull(
            matchHeadingFuzzy(
                "How do oceans affect Earth's climate system?",
                eduHeadings,
            ),
        )
    }

    @Test
    fun `sun question still fuzzy matches sun heading`() {
        assertEquals(
            "The Sun is the primary source of energy for Earth's climate system",
            matchHeadingFuzzy(
                "How does the Sun influence Earth's climate system",
                eduHeadings,
            ),
        )
    }

    @Test
    fun `weather climate question matches weather section`() {
        assertEquals(
            "Section IV How is Weather different from Climate",
            matchHeadingFuzzy(
                "What is the difference between weather and climate?",
                eduHeadings,
            ),
        )
    }
}
