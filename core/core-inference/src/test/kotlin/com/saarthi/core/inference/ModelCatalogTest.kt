package com.saarthi.core.inference

import com.saarthi.core.inference.model.PromptTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in each catalog entry's promptTier/defaultTemperature against the
 * exact values the removed name-matching logic (LiteRTInferenceEngine's old
 * isLargeGemma/baseTemperatureFor/isCompactModel) actually computed for that
 * entry's real file name — traced by hand against each entry's downloadUrl
 * at the time these fields were made data-driven, not "corrected" values.
 * A silent drift here (e.g. someone adding a new entry without setting
 * these, or editing one and leaving them stale) is exactly the "new model
 * silently inherits wrong config" failure class this migration closed —
 * this test is what would now catch it instead of a field report.
 */
class ModelCatalogTest {

    private val catalog = ModelCatalog()

    private fun entry(id: String) =
        catalog.allModels.find { it.id == id }
            ?: error("Catalog entry '$id' not found — update this test if it was intentionally renamed/removed")

    @Test
    fun `gemma4-e2b-it-qualcomm-sm8750 is LARGE tier at temperature 1point0`() {
        val model = entry("gemma4-e2b-it-qualcomm-sm8750")
        assertEquals(PromptTier.LARGE, model.promptTier)
        assertEquals(1.0f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `gemma4-e2b-it-litert is LARGE tier at temperature 1point0`() {
        val model = entry("gemma4-e2b-it-litert")
        assertEquals(PromptTier.LARGE, model.promptTier)
        assertEquals(1.0f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `gemma4-e4b-it-litert is LARGE tier at the tighter temperature 0point7`() {
        // The one entry that gets the tighter default — the old
        // baseTemperatureFor()'s gemma4+e4b-specific branch, matched via
        // the file path "…gemma-4-E4B-it…".
        val model = entry("gemma4-e4b-it-litert")
        assertEquals(PromptTier.LARGE, model.promptTier)
        assertEquals(0.7f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `gemma3n-e2b-it-litert-int4 is LARGE tier at temperature 1point0`() {
        val model = entry("gemma3n-e2b-it-litert-int4")
        assertEquals(PromptTier.LARGE, model.promptTier)
        assertEquals(1.0f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `gemma3n-e4b-it-litert-int4 is LARGE tier at temperature 1point0, NOT the tighter 0point7`() {
        // The old gemma4+e4b-specific 0.7 branch only matched "gemma4"/
        // "gemma-4"/"gemma 4" — this file's path ("…gemma-3n-E4B…") never
        // contained that, so despite also being an E4B variant it fell
        // through to the generic Gemma-3-family branch (1.0), not 0.7.
        val model = entry("gemma3n-e4b-it-litert-int4")
        assertEquals(PromptTier.LARGE, model.promptTier)
        assertEquals(1.0f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `gemma3-1b-it-litert-int4 is COMPACT tier but temperature 1point0, NOT the 0point8 else-branch`() {
        // The old baseTemperatureFor()'s "else -> 0.8f" branch was NEVER
        // actually reached by this model: its file name "gemma3-1b-it…"
        // contains the substring "gemma3", matching the generic
        // Gemma-3-family branch (1.0) before falling through to else.
        // Preserved exactly as today's real behavior, not "corrected".
        val model = entry("gemma3-1b-it-litert-int4")
        assertEquals(PromptTier.COMPACT, model.promptTier)
        assertEquals(1.0f, model.defaultTemperature, 0.0f)
    }

    @Test
    fun `every current catalog entry uses topK 64 (the field default, set explicitly nowhere)`() {
        // The old isLargeGemma-based topK=40 branch is dead code for every
        // model in today's catalog — every file name matches one of the
        // gemma3/gemma4 substring patterns, so topK=64 always won. No
        // entry needs an explicit override; this pins that fact so a
        // future catalog edit that silently changes it gets caught.
        catalog.allModels.forEach { model ->
            assertEquals("${model.id} topK", 64, model.topK)
        }
    }
}
