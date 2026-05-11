package com.saarthi.core.inference.prompt

import com.saarthi.core.inference.model.PackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptProviderTest {

    private val provider = SystemPromptProvider()

    // ── tierFor ────────────────────────────────────────────────────────────

    @Test
    fun tierFor_classifies_Gemma_3_1B_as_COMPACT() {
        assertEquals(SystemPromptProvider.ModelTier.COMPACT, provider.tierFor("Gemma 3 · Compact & Fast"))
        assertEquals(SystemPromptProvider.ModelTier.COMPACT, provider.tierFor("gemma3-1b-it-int4"))
    }

    @Test
    fun tierFor_classifies_Gemma_4_as_LARGE() {
        assertEquals(SystemPromptProvider.ModelTier.LARGE, provider.tierFor("Gemma 4 · Recommended"))
        assertEquals(SystemPromptProvider.ModelTier.LARGE, provider.tierFor("gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun tierFor_classifies_unknown_models_as_STANDARD() {
        assertEquals(SystemPromptProvider.ModelTier.STANDARD, provider.tierFor("Gemma 3n"))
        assertEquals(SystemPromptProvider.ModelTier.STANDARD, provider.tierFor("Some other model"))
    }

    @Test
    fun tierFor_treats_null_or_blank_as_STANDARD() {
        assertEquals(SystemPromptProvider.ModelTier.STANDARD, provider.tierFor(null))
        assertEquals(SystemPromptProvider.ModelTier.STANDARD, provider.tierFor(""))
    }

    // ── build: invariants every prompt must satisfy ────────────────────────

    @Test
    fun build_includes_Saarthi_identity_on_all_tiers() {
        for (modelName in listOf("Gemma 3 · Compact & Fast", "Gemma 3n", "Gemma 4 · Recommended")) {
            val prompt = provider.build(
                modelName = modelName,
                pack = PackType.BASE,
                languageInstruction = "",
                memoryContext = "",
            )
            assertTrue("Tier prompt for $modelName must mention Saarthi: $prompt", prompt.contains("Saarthi"))
        }
    }

    @Test
    fun build_never_makes_a_positive_self_identification_claim() {
        // The prompt legitimately contains the words "Gemma" / "Google" inside a
        // negative-constraint sentence ("Never call yourself Gemma, Google…").
        // The actual invariant is: the prompt must never make a POSITIVE claim
        // ("You are Gemma", "You are a language model"). Those would seed
        // self-identification leaks even before the runtime rewriter runs.
        val forbiddenPositiveClaims = listOf(
            "You are Gemma",
            "You are a language model",
            "You are a large language model",
            "You are an LLM",
            "You are Google",
            "developed by Google",
            "developed by DeepMind",
        )
        for (pack in PackType.values()) {
            for (modelName in listOf("Gemma 3 · Compact", "Gemma 3n", "Gemma 4 · Recommended")) {
                val prompt = provider.build(
                    modelName = modelName,
                    pack = pack,
                    languageInstruction = "",
                    memoryContext = "",
                )
                for (claim in forbiddenPositiveClaims) {
                    assertFalse(
                        "$modelName/$pack prompt makes positive identity claim '$claim'",
                        prompt.contains(claim),
                    )
                }
            }
        }
    }

    @Test
    fun build_appends_language_instruction_last() {
        val langLine = "Always reply in Hindi."
        val prompt = provider.build(
            modelName = "Gemma 4",
            pack = PackType.BASE,
            languageInstruction = langLine,
            memoryContext = "",
        )
        // Sandwich layout: directive at both ends. Bottom appearance still
        // required so the model sees it right before the user's turn.
        assertTrue(
            "Language instruction must be at the very end (proximity attention). Got:\n$prompt",
            prompt.trimEnd().endsWith(langLine),
        )
    }

    @Test
    fun build_prepends_language_instruction_at_top() {
        // Top-of-prompt placement anchors output language from the first
        // attention pass. Critical for low-resource languages on
        // Gemma 3n where bottom-only placement was being ignored.
        val langLine = "Always reply in Telugu."
        val prompt = provider.build(
            modelName = "Gemma 3n",
            pack = PackType.BASE,
            languageInstruction = langLine,
            memoryContext = "",
        )
        assertTrue(
            "Language instruction must start the prompt. Got:\n$prompt",
            prompt.trimStart().startsWith(langLine),
        )
    }

    @Test
    fun build_includes_language_instruction_at_both_ends() {
        val langLine = "Reply in Tamil."
        val prompt = provider.build(
            modelName = "Gemma 3n",
            pack = PackType.BASE,
            languageInstruction = langLine,
            memoryContext = "",
        )
        // Count occurrences — must be exactly 2 (top + bottom), not 1.
        val count = langLine.toRegex().findAll(prompt).count()
        assertEquals("Language directive must appear at top AND bottom", 2, count)
    }

    @Test
    fun build_appends_memory_section_when_supplied() {
        val mem = "- name: Rahul\n- city: Pune"
        val prompt = provider.build(
            modelName = "Gemma 4",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = mem,
        )
        assertTrue("Memory bullets must appear in prompt:\n$prompt", prompt.contains("Rahul"))
        assertTrue("Memory bullets must appear in prompt:\n$prompt", prompt.contains("Pune"))
    }

    @Test
    fun build_omits_memory_section_header_when_empty() {
        val prompt = provider.build(
            modelName = "Gemma 4",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "",
        )
        assertFalse(
            "Should not render memory section header when there are no facts",
            prompt.contains("Facts the USER has shared"),
        )
    }

    @Test
    fun build_memory_header_disambiguates_user_from_assistant() {
        // The earlier header "What you remember about the user:" caused
        // smaller models in non-English sessions to conflate "your name"
        // (the user's name from memory) with "your name" (the assistant's
        // own name) — the model would reply "Your name is Arjun" when
        // asked "What is your name?" in Telugu. New header explicitly
        // marks the facts as being about the user, not the assistant.
        val prompt = provider.build(
            modelName = "Gemma 3n",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "- name: Arjun",
        )
        assertTrue(
            "Memory section header must explicitly disambiguate from assistant identity. Got:\n$prompt",
            prompt.contains("Facts the USER has shared") &&
                prompt.contains("about the user, not about you"),
        )
    }

    @Test
    fun build_changes_text_per_pack() {
        val base = provider.build("Gemma 4", PackType.BASE, "", "")
        val kisan = provider.build("Gemma 4", PackType.KISAN, "", "")
        val money = provider.build("Gemma 4", PackType.MONEY, "", "")
        assertNotEquals("BASE and KISAN prompts must differ", base, kisan)
        assertNotEquals("BASE and MONEY prompts must differ", base, money)
    }

    @Test
    fun build_compact_tier_is_shorter_than_standard() {
        // A core invariant: 1B-tier prompts must stay tight to leave room for replies.
        val compact = provider.build("Gemma 3 · Compact & Fast", PackType.BASE, "", "")
        val standard = provider.build("Gemma 3n", PackType.BASE, "", "")
        assertTrue(
            "COMPACT prompt should be smaller than STANDARD (compact=${compact.length}, standard=${standard.length})",
            compact.length < standard.length,
        )
    }
}
