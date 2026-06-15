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

    /**
     * Guard the load-bearing coupling between marketing copy and runtime tier:
     * the active model name passed to [SystemPromptProvider.tierFor] IS the
     * catalog [ModelEntry.displayName] (see MainViewModel — `modelName =
     * catalogEntry.displayName`). The Kisan-pack capability gate and the
     * sampler/token-budget logic all key off that classification, so a future
     * rename of a model's displayName must not silently change its tier.
     *
     * Specifically: the compact 1B must classify COMPACT (so the Kisan chat
     * stays blocked on it), and nothing else may collapse into COMPACT.
     */
    @Test
    fun tierFor_over_every_catalog_displayName_is_stable() {
        val catalog = com.saarthi.core.inference.ModelCatalog()
        val byId = catalog.allModels.associateBy({ it.id }, { provider.tierFor(it.displayName) })

        assertEquals(
            "The compact 1B model must classify COMPACT so the Kisan chat is gated off on it",
            SystemPromptProvider.ModelTier.COMPACT,
            byId["gemma3-1b-it-litert-int4"],
        )
        // No other catalog model may classify COMPACT — that would wrongly
        // disable the Kisan chat on a capable model.
        catalog.allModels
            .filter { it.id != "gemma3-1b-it-litert-int4" }
            .forEach { model ->
                assertNotEquals(
                    "${model.displayName} must NOT classify COMPACT",
                    SystemPromptProvider.ModelTier.COMPACT,
                    provider.tierFor(model.displayName),
                )
            }
        // Gemma 4 family stays LARGE (richer prompt + larger token budget).
        listOf("gemma4-e2b-it-litert", "gemma4-e4b-it-litert", "gemma4-e2b-it-qualcomm-sm8750")
            .forEach { id ->
                assertEquals(
                    "$id must classify LARGE",
                    SystemPromptProvider.ModelTier.LARGE,
                    byId[id],
                )
            }
    }

    // ── supportsPackChat (knowledge-pack capability gate, all packs) ────────

    @Test
    fun supportsPackChat_is_false_only_on_the_compact_tier() {
        // The compact 1B loops on grounded pack prompts → pack chat is gated off
        // for EVERY pack (Kisan and any future pack), not just Kisan.
        val catalog = com.saarthi.core.inference.ModelCatalog()
        val compact = catalog.findById("gemma3-1b-it-litert-int4")!!
        assertFalse(provider.supportsPackChat(compact.displayName))

        // Every non-compact catalog model must allow pack chat.
        catalog.allModels
            .filter { it.id != "gemma3-1b-it-litert-int4" }
            .forEach { model ->
                assertTrue(
                    "${model.displayName} must support pack chat",
                    provider.supportsPackChat(model.displayName),
                )
            }
    }

    // ── build: invariants every prompt must satisfy ────────────────────────

    @Test
    fun build_includes_Saarthi_identity_on_instruction_following_tiers() {
        // STANDARD (Gemma 3n) and LARGE (Gemma 4) follow a system prompt, so the
        // Saarthi identity must be present. COMPACT (Gemma 3 1B) is handled
        // separately below — it intentionally gets NO system prompt because the
        // 1B model parrots any system text back as if the user said it.
        for (modelName in listOf("Gemma 3n", "Gemma 4 · Recommended")) {
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
    fun build_returns_empty_system_prompt_for_COMPACT_tier() {
        // Documented contract (see SystemPromptProvider.build): the COMPACT
        // (Gemma 3 1B) tier returns an EMPTY system prompt. The tiny model
        // cannot separate system instructions from user content — any system
        // text ("You are Saarthi…") gets echoed back, so we send nothing
        // system-side and let the user message stand alone.
        val prompt = provider.build(
            modelName = "Gemma 3 · Compact & Fast",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "",
        )
        assertTrue("COMPACT tier must return an empty system prompt, got: $prompt", prompt.isEmpty())
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
            prompt.contains("Facts the USER shared"),
        )
    }

    @Test
    fun build_memory_header_scopes_to_this_chat_and_disambiguates_identity() {
        // Memory is per-chat (v1.0.24). The header must say "THIS chat" so
        // (a) the model treats the facts as conversation-scoped, and
        // (b) pronoun antecedents resolve to the user, not the assistant.
        // Older header "What you remember about the user:" caused the
        // Telugu-session "your name is Arjun" antecedent leak.
        val prompt = provider.build(
            modelName = "Gemma 3n",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "- name: Arjun",
        )
        assertTrue(
            "Memory section header must scope to THIS chat. Got:\n$prompt",
            prompt.contains("Facts the USER shared in THIS chat"),
        )
        assertTrue(
            "Memory section header must disambiguate user-facts from assistant identity",
            prompt.contains("about the user, not about you"),
        )
    }

    @Test
    fun build_inserts_time_context_when_supplied() {
        val time = "Current local time is 21:14 on Mon — it is evening."
        val prompt = provider.build(
            modelName = "Gemma 4",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "",
            timeContext = time,
        )
        assertTrue(
            "Time context must appear in prompt. Got:\n$prompt",
            prompt.contains(time),
        )
    }

    @Test
    fun build_omits_time_context_when_blank() {
        val prompt = provider.build(
            modelName = "Gemma 4",
            pack = PackType.BASE,
            languageInstruction = "",
            memoryContext = "",
            timeContext = "",
        )
        assertFalse(
            "Empty time context must not leave a stray 'Current local time' line",
            prompt.contains("Current local time"),
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
