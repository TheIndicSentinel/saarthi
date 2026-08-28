package com.saarthi.core.inference

import com.saarthi.core.inference.model.PromptTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 *
 * Also locks the supply-chain pins on every production catalog entry:
 * non-null lowercase SHA-256, Hugging Face URL pinned to an immutable
 * 40-char commit (never resolve/main), and one hash per distinct fileName.
 * [com.saarthi.core.inference.model.ModelEntry.expectedSha256] stays
 * nullable so tests/helpers can skip verify; this class is the lock that
 * the shipping catalog never ships a null hash or a mutable URL.
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

    @Test
    fun `every catalog entry pins a lowercase hex SHA-256`() {
        val sha256 = Regex("^[0-9a-f]{64}$")
        assertTrue("catalog must not be empty", catalog.allModels.isNotEmpty())
        catalog.allModels.forEach { model ->
            assertNotNull(
                "${model.id} expectedSha256 must be set — null skips verifyChecksum at download time",
                model.expectedSha256,
            )
            assertTrue(
                "${model.id} expectedSha256 must be lowercase hex SHA-256, was: ${model.expectedSha256}",
                sha256.matches(model.expectedSha256!!),
            )
        }
    }

    @Test
    fun `every catalog downloadUrl is pinned to an immutable Hugging Face revision`() {
        val pinnedRevision = Regex("/resolve/[0-9a-f]{40}/")
        assertTrue("catalog must not be empty", catalog.allModels.isNotEmpty())
        catalog.allModels.forEach { model ->
            assertTrue(
                "${model.id} downloadUrl must contain /resolve/<40-char-hex-sha>/, was: ${model.downloadUrl}",
                pinnedRevision.containsMatchIn(model.downloadUrl),
            )
            assertFalse(
                "${model.id} downloadUrl must not use mutable /resolve/main/, was: ${model.downloadUrl}",
                model.downloadUrl.contains("/resolve/main"),
            )
        }
    }

    @Test
    fun `expectedSha256 is unique per distinct fileName`() {
        val byFileName = catalog.allModels.groupBy { it.fileName }
        byFileName.forEach { (fileName, entries) ->
            val hashes = entries.map { it.expectedSha256 }.toSet()
            assertEquals(
                "two entries may not claim different hashes for fileName '$fileName': " +
                    "$hashes (ids=${entries.map { it.id }})",
                1,
                hashes.size,
            )
        }
        val byHash = catalog.allModels.groupBy { it.expectedSha256 }
        byHash.forEach { (hash, entries) ->
            val names = entries.map { it.fileName }.toSet()
            assertEquals(
                "expectedSha256 $hash claimed by multiple fileNames: $names (ids=${entries.map { it.id }})",
                1,
                names.size,
            )
        }
    }

    @Test
    fun `Recommended catalog models do not require a Hugging Face token`() {
        val recommended = catalog.allModels.filter { "Recommended" in it.tags }
        assertTrue("catalog must have a Recommended model for first-run auto-pick", recommended.isNotEmpty())
        recommended.forEach { model ->
            assertFalse(
                "${model.id} is Recommended (first-run auto-pick) so it must be a public repo, was: ${model.downloadUrl}",
                model.requiresHuggingFaceAuth,
            )
        }
    }

    @Test
    fun `only google-org catalog URLs require a Hugging Face token`() {
        catalog.allModels.forEach { model ->
            val google = model.downloadUrl.contains("huggingface.co/google/", ignoreCase = true)
            assertEquals(
                "${model.id} requiresHuggingFaceAuth must match google/ host, url=${model.downloadUrl}",
                google,
                model.requiresHuggingFaceAuth,
            )
        }
    }
}
