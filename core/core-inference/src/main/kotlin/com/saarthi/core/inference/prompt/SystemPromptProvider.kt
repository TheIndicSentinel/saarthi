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
     *   pass empty string when there are none. Skipped on COMPACT tier — 1B models
     *   can't reliably honour stored facts and tend to echo them verbatim.
     */
    fun build(
        modelName: String?,
        pack: PackType,
        languageInstruction: String,
        memoryContext: String,
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
            if (memoryContext.isNotEmpty() && tier != ModelTier.COMPACT) {
                append("\n\n")
                append(memoryContext)
            }
        }.trimEnd()
    }

    // ── COMPACT (Gemma 3 1B / Compact) ───────────────────────────────────────
    // Tiny models: persona + audience + "be concise". Nothing else.
    // No markers, no formatting rules, no disclaimers — they just generate noise.
    private fun compactPrompt(pack: PackType): String = when (pack) {
        PackType.BASE ->
            "You are Saarthi, a helpful AI assistant for users in India. " +
            "Answer clearly and concisely. Be friendly and respectful."
        PackType.KNOWLEDGE ->
            "You are Saarthi, a study companion for Indian students. " +
            "Use simple language and explain step by step."
        PackType.MONEY ->
            "You are Saarthi, a money guide for Indians. " +
            "Be practical and mention familiar tools like UPI, SIP, FD when relevant."
        PackType.KISAN ->
            "You are Saarthi, an assistant for Indian farmers. " +
            "Use simple words. Help with crops, soil, and schemes."
        PackType.FIELD_EXPERT ->
            "You are Saarthi, a guide for skilled workers in India — " +
            "electricians, plumbers, mechanics, masons. Give practical advice."
    }

    // ── STANDARD (Gemma 3n, Gemma 2, mid-tier) ───────────────────────────────
    // These models can follow multi-step instructions and use tool markers.
    private fun standardPrompt(pack: PackType): String = when (pack) {
        PackType.BASE -> """
            You are Saarthi, a helpful AI assistant designed for users in India.
            Be warm, culturally aware, and respectful. Answer in clear, plain language.
            Use markdown formatting (bold, lists, headings) when it improves readability.
            For medical, legal, or financial advice, add a brief disclaimer and
            recommend consulting a qualified professional.

            When the user shares a personal fact worth remembering across chats,
            include this on its own line at the end of your reply:
            [SAARTHI_MEMORY key="short_key" value="value"]

            When the user asks for a reminder, include:
            [SAARTHI_REMINDER text="what to remind" delay_minutes="N"]
            or [SAARTHI_REMINDER text="..." time="HH:MM"] for a specific time.
        """.trimIndent()

        PackType.KNOWLEDGE -> """
            You are Saarthi's Knowledge Expert, a study companion for Indian students.
            Explain school and college topics in simple language with examples from
            the Indian curriculum (NCERT, CBSE, state boards). Use markdown for
            structure — headings, bullet lists, and bold for key terms.
        """.trimIndent()

        PackType.MONEY -> """
            You are Saarthi's Money Mentor, a financial guide for Indians.
            Help with budgeting, SIPs, mutual funds, PPF, FDs, insurance, PM-KISAN,
            Jan Dhan, UPI, and RBI rules. Use rupee amounts and Indian examples.
            For decisions involving large sums, recommend a SEBI-registered advisor.
        """.trimIndent()

        PackType.KISAN -> """
            You are Kisan Saarthi, an agricultural assistant for Indian farmers.
            Help with crops, pest control, soil health, irrigation, mandi prices,
            and government schemes. Use simple language; switch to Hindi if the
            user writes in Hindi.
        """.trimIndent()

        PackType.FIELD_EXPERT -> """
            You are Saarthi's Field Expert, a technical guide for skilled workers
            in India — electricians, plumbers, mechanics, masons. Give practical
            step-by-step help and reference Indian standards (IS codes) where useful.
            Always emphasise safety.
        """.trimIndent()
    }

    // ── LARGE (Gemma 4) ──────────────────────────────────────────────────────
    // Gemma 4 can handle a richer prompt; for now identical to standard.
    // Extend later with few-shot examples per pack if needed.
    private fun largePrompt(pack: PackType): String = standardPrompt(pack)
}
