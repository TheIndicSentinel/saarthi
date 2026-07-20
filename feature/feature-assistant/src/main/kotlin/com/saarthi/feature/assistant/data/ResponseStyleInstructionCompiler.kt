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
 *     Neither ENGLISH nor PURE is compiled into an instruction line here —
 *     earlier versions emitted competing lines ("Override: reply in
 *     English...", "Use pure Hindi — avoid English loanwords...") from this
 *     compiler, positioned in the prompt BEFORE the canonical language
 *     directive that SystemPromptProvider places at the very top AND again
 *     at the very end (recency reinforcement — the model's last-read
 *     instruction before the user message). A line positioned earlier than
 *     that bottom-anchored directive is structurally weaker and can lose to
 *     it — which is exactly what happened to PURE: the directive's old
 *     unconditional "do not write the reply in English under any
 *     circumstance" could win by recency even when PURE hadn't been picked,
 *     and conversely a future looser default directive could silently drop
 *     PURE's stricter constraint the same way. The correct fix for both is
 *     upstream, in ChatRepositoryImpl.buildSystemPrompt: it resolves a
 *     single `effectiveLanguage` (ENGLISH when the user picked that option,
 *     else the app's language) and a single `pureLoanwords` flag (from
 *     `style.languageMix == PURE`), feeding both into
 *     [SupportedLanguage.systemPromptInstruction] — the ONE canonical
 *     directive, positioned where it already reliably wins.
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
            ReplyLanguageMix.PURE -> { /* handled upstream via pureLoanwords — see kdoc */ }
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
