package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.ReplyLanguageMix
import com.saarthi.core.i18n.ReplyLength
import com.saarthi.core.i18n.ReplyTone
import com.saarthi.core.i18n.ResponseStyle
import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the response-style -> system-prompt-instruction
 * compilation this Settings screen depends on. Covers what the guardrails
 * review flagged: safety must never be suppressible from here, "pure"/"eng"
 * must be relative to the actual output language (not hardcoded Hindi), and
 * two known-conflicting preference pairs must resolve deterministically
 * rather than emitting contradictory lines in the same instruction block.
 */
class ResponseStyleInstructionCompilerTest {

    private val compiler = ResponseStyleInstructionCompiler()

    @Test
    fun `every preference at default produces an empty instruction`() {
        val result = compiler.compile(ResponseStyle(), SupportedLanguage.HINDI, grounded = false)
        assertEquals("", result)
    }

    // ── Safety invariant ─────────────────────────────────────────────────────

    @Test
    fun `no combination of preferences ever emits a disclaimer-suppression instruction`() {
        val toneOptions = ReplyTone.entries
        val lengthOptions = ReplyLength.entries
        val languageOptions = ReplyLanguageMix.entries
        for (tone in toneOptions) {
            for (length in lengthOptions) {
                for (mix in languageOptions) {
                    for (examples in listOf(true, false)) {
                        for (grounded in listOf(true, false)) {
                            val style = ResponseStyle(
                                length = length, tone = tone, languageMix = mix, includeExamples = examples,
                            )
                            val result = compiler.compile(style, SupportedLanguage.ENGLISH, grounded)
                            assertFalse(
                                "instruction must never mention skipping/suppressing disclaimers: $result",
                                result.contains("disclaimer", ignoreCase = true) &&
                                    (result.contains("skip", ignoreCase = true) || result.contains("avoid", ignoreCase = true)),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Language contract: relative to the actual output language ───────────

    @Test
    fun `pure references the actual app language, not a hardcoded one`() {
        val tamil = compiler.compile(
            ResponseStyle(languageMix = ReplyLanguageMix.PURE), SupportedLanguage.TAMIL, grounded = false,
        )
        assertTrue("expected Tamil to be named: $tamil", tamil.contains("Tamil"))
        assertFalse("must not hardcode Hindi for a Tamil user: $tamil", tamil.contains("Hindi"))

        val telugu = compiler.compile(
            ResponseStyle(languageMix = ReplyLanguageMix.PURE), SupportedLanguage.TELUGU, grounded = false,
        )
        assertTrue("expected Telugu to be named: $telugu", telugu.contains("Telugu"))
    }

    @Test
    fun `english override is explicit, not a bare contradiction`() {
        val result = compiler.compile(
            ResponseStyle(languageMix = ReplyLanguageMix.ENGLISH), SupportedLanguage.HINDI, grounded = false,
        )
        assertTrue("must say Override so it doesn't silently fight systemPromptInstruction: $result", result.contains("Override"))
        assertTrue(result.contains("English"))
    }

    @Test
    fun `mix (Hinglish) emits no extra language instruction`() {
        val result = compiler.compile(
            ResponseStyle(languageMix = ReplyLanguageMix.MIX), SupportedLanguage.HINDI, grounded = false,
        )
        assertEquals("", result)
    }

    // ── Conflict resolution ──────────────────────────────────────────────────

    @Test
    fun `long length wins over includeExamples=false instead of contradicting it`() {
        val result = compiler.compile(
            ResponseStyle(length = ReplyLength.LONG, includeExamples = false),
            SupportedLanguage.ENGLISH,
            grounded = false,
        )
        assertTrue("long must still ask for examples: $result", result.contains("examples when useful"))
        assertFalse("must not also say to avoid examples: $result", result.contains("Avoid worked examples"))
    }

    @Test
    fun `short length with includeExamples=false emits only the avoid-examples line`() {
        val result = compiler.compile(
            ResponseStyle(length = ReplyLength.SHORT, includeExamples = false),
            SupportedLanguage.ENGLISH,
            grounded = false,
        )
        assertTrue(result.contains("short"))
        assertTrue(result.contains("Avoid worked examples"))
    }

    @Test
    fun `grounded turns skip the length constraint entirely`() {
        val result = compiler.compile(
            ResponseStyle(length = ReplyLength.SHORT), SupportedLanguage.ENGLISH, grounded = true,
        )
        assertFalse("length must not fight a grounded turn's own quoting instruction: $result", result.contains("short"))
    }

    @Test
    fun `grounded turns still apply tone and language preferences`() {
        val result = compiler.compile(
            ResponseStyle(tone = ReplyTone.FORMAL, languageMix = ReplyLanguageMix.ENGLISH),
            SupportedLanguage.HINDI,
            grounded = true,
        )
        assertTrue(result.contains("formal"))
        assertTrue(result.contains("Override"))
    }

    // ── Individual axes (non-conflicting cases) ──────────────────────────────

    @Test
    fun `tone options each produce their own distinct instruction`() {
        val warm = compiler.compile(ResponseStyle(tone = ReplyTone.WARM), SupportedLanguage.ENGLISH, grounded = false)
        val formal = compiler.compile(ResponseStyle(tone = ReplyTone.FORMAL), SupportedLanguage.ENGLISH, grounded = false)
        val balanced = compiler.compile(ResponseStyle(tone = ReplyTone.BALANCED), SupportedLanguage.ENGLISH, grounded = false)
        assertTrue(warm.contains("warm"))
        assertTrue(formal.contains("formal"))
        assertEquals("", balanced)
    }

    @Test
    fun `medium length produces no extra instruction`() {
        val result = compiler.compile(ResponseStyle(length = ReplyLength.MEDIUM), SupportedLanguage.ENGLISH, grounded = false)
        assertEquals("", result)
    }
}
