package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SupportedLanguage is the single source of truth for language coverage
 * across the app — every screen reads from it (greetings, suggestions,
 * input hints, app name in script). Drift here ripples into every
 * surface, so the invariants below lock the contract.
 *
 *   • Code-based lookup must round-trip and fall back to HINDI for
 *     unknown codes (we ship to mostly-Hindi-speaking users).
 *
 *   • systemPromptInstruction must be non-empty for every language —
 *     including English — because ChatRepositoryImpl now always passes
 *     it (an English-selected user was getting Hindi replies until we
 *     fixed this in v1.0.14).
 *
 *   • Every per-language localised string (greeting, suggestions, etc.)
 *     must be non-blank — a blank value would render an empty UI cell.
 */
class SupportedLanguageTest {

    // ── fromCode ───────────────────────────────────────────────────────

    @Test
    fun fromCode_round_trips_each_language() {
        for (lang in SupportedLanguage.entries) {
            assertEquals(
                "fromCode(${lang.code}) must return $lang",
                lang,
                SupportedLanguage.fromCode(lang.code),
            )
        }
    }

    @Test
    fun fromCode_falls_back_to_HINDI_for_unknown_code() {
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode("xx"))
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode(""))
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode("zh"))
    }

    // ── systemPromptInstruction ─────────────────────────────────────────

    @Test
    fun systemPromptInstruction_is_non_blank_for_every_language() {
        for (lang in SupportedLanguage.entries) {
            assertFalse(
                "${lang.englishName} must have a non-blank prompt instruction",
                lang.systemPromptInstruction.isBlank(),
            )
        }
    }

    @Test
    fun systemPromptInstruction_for_English_is_distinct() {
        // Regression guard: v1.0.14 fixed a bug where English's instruction
        // was empty, causing English-selected users to get Hindi replies.
        // The English line must explicitly say "reply in English".
        val english = SupportedLanguage.ENGLISH.systemPromptInstruction
        assertTrue(
            "English instruction must mention English. Got: '$english'",
            english.contains("English", ignoreCase = true),
        )
    }

    @Test
    fun systemPromptInstruction_for_non_English_uses_native_name() {
        // Non-English instructions should reference the native script name
        // so a model that fixates on the prompt has the right token to
        // anchor on.
        val hindi = SupportedLanguage.HINDI.systemPromptInstruction
        assertTrue(
            "Hindi instruction must contain the native script. Got: '$hindi'",
            hindi.contains(SupportedLanguage.HINDI.nativeName),
        )
        val tamil = SupportedLanguage.TAMIL.systemPromptInstruction
        assertTrue(
            "Tamil instruction must contain the native script. Got: '$tamil'",
            tamil.contains(SupportedLanguage.TAMIL.nativeName),
        )
    }

    @Test
    fun systemPromptInstruction_differs_between_English_and_Hindi() {
        // The two must NOT collide on a default — that would resurface the
        // English-treated-as-default bug in a different form.
        assertNotEquals(
            SupportedLanguage.ENGLISH.systemPromptInstruction,
            SupportedLanguage.HINDI.systemPromptInstruction,
        )
    }

    // ── per-language UI strings ─────────────────────────────────────────

    @Test
    fun every_language_has_non_blank_native_and_english_name() {
        for (lang in SupportedLanguage.entries) {
            assertFalse("${lang} nativeName blank", lang.nativeName.isBlank())
            assertFalse("${lang} englishName blank", lang.englishName.isBlank())
            assertFalse("${lang} code blank", lang.code.isBlank())
        }
    }

    @Test
    fun every_language_has_non_blank_greeting_and_avatar_label() {
        for (lang in SupportedLanguage.entries) {
            assertFalse("${lang.englishName} greeting blank", lang.greeting.isBlank())
            assertFalse("${lang.englishName} avatarLabel blank", lang.avatarLabel.isBlank())
            assertFalse("${lang.englishName} firstLetter blank", lang.firstLetter.isBlank())
            assertFalse("${lang.englishName} appName blank", lang.appName.isBlank())
        }
    }

    @Test
    fun every_language_has_at_least_three_suggestion_chips() {
        // The empty-chat home screen renders 3-4 quick-suggestion chips.
        // Fewer than 3 leaves visible empty space; zero crashes the layout.
        for (lang in SupportedLanguage.entries) {
            assertTrue(
                "${lang.englishName} must have ≥3 suggestion chips. Got ${lang.suggestions.size}",
                lang.suggestions.size >= 3,
            )
            for (s in lang.suggestions) {
                assertFalse("${lang.englishName} has a blank suggestion", s.isBlank())
            }
        }
    }

    @Test
    fun every_language_has_unique_code() {
        val codes = SupportedLanguage.entries.map { it.code }
        assertEquals("Language codes must be unique", codes.size, codes.toSet().size)
    }

    @Test
    fun every_language_has_distinct_native_name() {
        // Two languages sharing the same native-name string would confuse
        // the language picker — users couldn't tell which one is selected.
        val names = SupportedLanguage.entries.map { it.nativeName }
        assertEquals("Native names must be unique", names.size, names.toSet().size)
    }
}
