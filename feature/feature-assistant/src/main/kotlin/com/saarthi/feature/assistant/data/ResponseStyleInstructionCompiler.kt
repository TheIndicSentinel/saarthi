package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.ReplyLanguageMix
import com.saarthi.core.i18n.ReplyLength
import com.saarthi.core.i18n.ReplyTone
import com.saarthi.core.i18n.ResponseStyle
import com.saarthi.core.i18n.SupportedLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles the user's Settings -> Response style preferences into a single
 * instruction block appended to the system prompt. Pure function of its
 * inputs — no I/O, no engine/tier awareness (SystemPromptProvider already
 * gates COMPACT tier by never calling this at all, since it sends no system
 * prompt for that tier).
 *
 * Safety is NOT a configurable input here, on purpose. SystemPromptProvider's
 * own baseline instruction ("add a disclaimer only for personalized medical
 * diagnosis, specific legal advice, or tailored investment advice — never for
 * general explanations") is already narrowly scoped and always active;
 * nothing compiled here can weaken or suppress it. An earlier version of this
 * logic let a "Show disclaimers" toggle emit "Skip safety/medical disclaimers
 * unless the user asks" — that setting has been removed entirely rather than
 * fixed in place, since there is no safe, non-critical subset of it worth
 * keeping (the baseline instruction was already minimal/well-scoped, so
 * disabling it had no legitimate cosmetic use case).
 *
 * Priority order when preferences could conflict (highest first):
 *  1. Factual/task requirements — a grounded (document/Kisan) turn's own
 *     "quote verbatim, don't invent" instruction always wins, so [length] is
 *     skipped entirely on grounded turns rather than fighting it.
 *  2. Language — code-switching preference, relative to whatever output
 *     language [language] resolves to elsewhere in the prompt
 *     (SupportedLanguage.systemPromptInstruction).
 *
 *     ENGLISH is NOT compiled into an instruction line here — an earlier
 *     version emitted "Override: reply in English...", but
 *     SystemPromptProvider places the canonical language directive both at
 *     the very top of the prompt AND again at the very end (recency
 *     reinforcement — the model's last-read instruction before the user
 *     message), specifically so it wins. A second, competing "Override"
 *     line placed earlier in the prompt was structurally weaker than that
 *     bottom-anchored directive and could lose to it. The correct fix is
 *     upstream, in ChatRepositoryImpl.buildSystemPrompt: it resolves a
 *     single `effectiveLanguage` (ENGLISH when the user picked this option,
 *     else the app's language) and feeds THAT into the canonical directive
 *     itself, so there is only ever one source of truth for output
 *     language, positioned where it already reliably wins. PURE has no such
 *     conflict — it never asks for a different language than the app
 *     setting, only a stricter register of the same one — so it's still
 *     compiled here as an additional constraint.
 *  3. Tone
 *  4. Length
 *  5. Examples — fully independent of length now. LONG used to bake in
 *     "with examples", which made [ResponseStyle.includeExamples] silently
 *     inert whenever length was LONG (toggling it produced identical
 *     output). LONG now asks only for detail, never examples, so the
 *     examples toggle is honoured the same way at every length.
 */
@Singleton
class ResponseStyleInstructionCompiler @Inject constructor() {

    fun compile(style: ResponseStyle, language: SupportedLanguage, grounded: Boolean): String {
        val lines = mutableListOf<String>()

        when (style.languageMix) {
            ReplyLanguageMix.PURE ->
                lines += "Use pure ${language.englishName} — avoid English loanwords or code-switching."
            ReplyLanguageMix.ENGLISH -> { /* handled upstream via effectiveLanguage — see kdoc */ }
            ReplyLanguageMix.MIX -> { /* natural code-switching default — no extra instruction */ }
        }

        when (style.tone) {
            ReplyTone.WARM -> lines += "Use a warm, friendly tone."
            ReplyTone.FORMAL -> lines += "Use a formal, professional tone."
            ReplyTone.BALANCED -> { /* no extra instruction */ }
        }

        if (!grounded) {
            when (style.length) {
                ReplyLength.SHORT -> lines += "Keep replies short (1–2 sentences)."
                ReplyLength.LONG -> lines += "Give detailed, thorough replies."
                ReplyLength.MEDIUM -> { /* no extra instruction */ }
            }
        }

        if (!style.includeExamples) {
            lines += "Avoid worked examples; explain concepts without illustrations."
        }

        return lines.joinToString(separator = " ")
    }
}
