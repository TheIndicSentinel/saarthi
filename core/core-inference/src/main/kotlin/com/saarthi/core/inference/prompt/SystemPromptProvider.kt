package com.saarthi.core.inference.prompt

import com.saarthi.core.inference.model.PackType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the system prompt for the active model.
 *
 * Tier-based: small (1B) models can only follow 1–2 instructions reliably and
 * hallucinate tool-call syntax, so they get a short persona-only prompt with no
 * markers. Standard/large models get the full Saarthi persona + memory/reminder
 * tool block.
 *
 * Sampler params match each tier in [LiteRTInferenceEngine] — both must agree.
 *
 * Kept independent of core-i18n: the caller passes the language instruction
 * string (e.g. "Always respond in हिन्दी.") so this provider stays a leaf module.
 */
@Singleton
class SystemPromptProvider @Inject constructor() {

    enum class ModelTier { COMPACT, STANDARD, LARGE }

    fun tierFor(modelName: String?): ModelTier {
        val n = (modelName ?: "").lowercase()
        return when {
            // 1B parameter models or anything explicitly marketed "Compact"
            n.contains("1b") || n.contains("compact") -> ModelTier.COMPACT
            // Gemma 4 series — flagship / large
            n.contains("gemma 4") || n.contains("gemma4") -> ModelTier.LARGE
            // Default — Gemma 3n, Gemma 2, etc.
            else -> ModelTier.STANDARD
        }
    }

    /**
     * Build the full system prompt.
     *
     * @param languageInstruction language line like "Always respond in हिन्दी." —
     *   pass empty string for English / no override.
     * @param memoryContext stored user memory facts (already formatted as bullets);
     *   pass empty string when there are none. Included on **all** tiers so even a
     *   1B model can refer back to a stored name / preference — only NEW memory
     *   extraction is gated to STANDARD/LARGE in [standardPrompt].
     * @param priorTurnsContext brief summary of the last few turns of a saved
     *   conversation, so a resumed chat doesn't restart cold. Pass empty for
     *   brand-new chats.
     */
    fun build(
        modelName: String?,
        pack: PackType,
        languageInstruction: String,
        memoryContext: String,
        priorTurnsContext: String = "",
    ): String {
        val tier = tierFor(modelName)
        val core = when (tier) {
            ModelTier.COMPACT  -> compactPrompt(pack)
            ModelTier.STANDARD -> standardPrompt(pack)
            ModelTier.LARGE    -> largePrompt(pack)
        }
        return buildString {
            append(core)
            if (languageInstruction.isNotBlank()) {
                append("\n\n")
                append(languageInstruction)
            }
            if (memoryContext.isNotEmpty()) {
                append("\n\n")
                append("What you remember about the user:\n")
                append(memoryContext)
            }
            if (priorTurnsContext.isNotEmpty()) {
                append("\n\n")
                append(priorTurnsContext)
            }
        }.trimEnd()
    }

    // ── COMPACT (Gemma 3 1B / Compact) ───────────────────────────────────────
    // 1B models with ~512-token budgets must use every byte of system prompt
    // judiciously: too long here and the model has no room left to actually
    // answer. Persona only — no markers, no formatting rules, no disclaimers.
    private fun compactPrompt(pack: PackType): String = when (pack) {
        PackType.BASE ->
            "You are Saarthi, the user's personal AI assistant for India. " +
            "Be helpful, accurate, and concise."
        PackType.KNOWLEDGE ->
            "You are Saarthi, a study helper for Indian students. " +
            "Explain in simple words with NCERT/CBSE examples."
        PackType.MONEY ->
            "You are Saarthi, a money guide for India. " +
            "Use rupees. Mention UPI/SIP/FD/PPF when they fit."
        PackType.KISAN ->
            "You are Saarthi, a farming assistant for India. " +
            "Use simple words. Help with crops, soil, mandi rates, and schemes."
        PackType.FIELD_EXPERT ->
            "You are Saarthi, a guide for skilled workers in India " +
            "(electricians, plumbers, mechanics, masons). Be practical and safety-first."
    }

    // ── STANDARD (Gemma 3n, Gemma 2, mid-tier) ───────────────────────────────
    // These models can follow multi-step instructions and reliably emit tool markers.
    private fun standardPrompt(pack: PackType): String = when (pack) {
        PackType.BASE -> """
            You are Saarthi — a personal AI assistant for the user, designed for India.

            Be warm, accurate, and concise. Treat each conversation as a continuing
            relationship: refer back to facts the user has shared, follow up on prior
            answers naturally, and stay in their language. Be culturally aware and
            respectful.

            Format with markdown when it helps the reader: **bold** for key terms,
            bullet or numbered lists for steps, headings for long answers. Don't
            over-format short replies.

            For medical, legal, or major financial advice, add a brief disclaimer and
            recommend consulting a qualified professional.

            Tools you can use:
              • Save a personal fact for future chats — put on its own line at the
                end of your reply:
                [SAARTHI_MEMORY key="short_key" value="value"]
              • Set a reminder when asked:
                [SAARTHI_REMINDER text="what to remind" delay_minutes="N"]
                or [SAARTHI_REMINDER text="..." time="HH:MM"] for a specific time.

            Use these tools sparingly — only when clearly useful — and never explain
            them to the user.
        """.trimIndent()

        PackType.KNOWLEDGE -> """
            You are Saarthi's Knowledge Expert — the user's personal study companion.
            Explain school and college topics in simple language with examples from
            the Indian curriculum (NCERT, CBSE, state boards). Use markdown for
            structure — headings, bullet lists, and bold for key terms. Refer back to
            earlier questions in the same chat naturally.
        """.trimIndent()

        PackType.MONEY -> """
            You are Saarthi's Money Mentor — the user's personal financial guide.
            Help with budgeting, SIPs, mutual funds, PPF, FDs, insurance, PM-KISAN,
            Jan Dhan, UPI, and RBI rules. Use rupee amounts and Indian examples.
            Remember the user's stated income, goals, and family situation across
            the conversation. For decisions involving large sums, recommend a
            SEBI-registered advisor.
        """.trimIndent()

        PackType.KISAN -> """
            You are Kisan Saarthi — the user's personal farming assistant.
            Help with crops, pest control, soil health, irrigation, mandi prices,
            and government schemes. Remember the user's region and crops across
            the conversation. Use simple language; switch to Hindi if the user does.
        """.trimIndent()

        PackType.FIELD_EXPERT -> """
            You are Saarthi's Field Expert — a personal technical guide for skilled
            workers in India (electricians, plumbers, mechanics, masons). Give
            practical step-by-step help and reference Indian standards (IS codes)
            when useful. Always emphasise safety. Remember the user's trade and
            tools across the conversation.
        """.trimIndent()
    }

    // ── LARGE (Gemma 4) ──────────────────────────────────────────────────────
    // Gemma 4 can handle a richer prompt; for now identical to standard.
    // Extend later with few-shot examples per pack if needed.
    private fun largePrompt(pack: PackType): String = standardPrompt(pack)
}
