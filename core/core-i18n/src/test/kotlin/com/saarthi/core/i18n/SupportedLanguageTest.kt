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

    // ── Notifications, permission dialog, snackbars ──────────────────────
    //
    // Regression guard for the field report that started this block: Telugu
    // selected, but the battery-optimization dialog and several notifications
    // showed Hindi or English. Two distinct bugs caused it — (1) notifPermTitle
    // /allowLabel/notNowLabel had an `else -> English` fallback that silently
    // covered every language except Hindi/Marathi, and (2) InferenceService's
    // notification text was hardcoded English with no per-language branches at
    // all. Every property below uses an exhaustive `when` (no `else`), so the
    // Kotlin compiler itself now rejects a missing language — but nothing
    // stopped a future edit from reintroducing an `else -> "..."` catch-all,
    // which is exactly what these tests lock down.

    private fun assertNonBlankForEveryLanguage(label: String, value: SupportedLanguage.() -> String) {
        for (lang in SupportedLanguage.entries) {
            assertFalse("${lang.englishName} $label must be non-blank", lang.value().isBlank())
        }
    }

    @Test
    fun every_language_has_non_blank_battery_dialog_strings() {
        assertNonBlankForEveryLanguage("notifPermTitle") { notifPermTitle }
        assertNonBlankForEveryLanguage("batteryOptExplanation") { batteryOptExplanation }
        assertNonBlankForEveryLanguage("allowLabel") { allowLabel }
        assertNonBlankForEveryLanguage("notNowLabel") { notNowLabel }
    }

    @Test
    fun every_language_has_non_blank_inference_notification_strings() {
        assertNonBlankForEveryLanguage("loadingModelTitle") { loadingModelTitle }
        assertNonBlankForEveryLanguage("loadingModelBody") { loadingModelBody }
        assertNonBlankForEveryLanguage("generatingResponseTitle") { generatingResponseTitle }
        assertNonBlankForEveryLanguage("generatingResponseBody") { generatingResponseBody }
    }

    @Test
    fun every_language_has_non_blank_snackbar_strings() {
        assertNonBlankForEveryLanguage("attachmentsNeedLargerModel") { attachmentsNeedLargerModel }
        assertNonBlankForEveryLanguage("streamFailedRetry") { streamFailedRetry }
        assertNonBlankForEveryLanguage("voiceNoMatch") { voiceNoMatch }
        assertNonBlankForEveryLanguage("voiceMicPermissionNeeded") { voiceMicPermissionNeeded }
        assertNonBlankForEveryLanguage("voiceServiceUnavailable") { voiceServiceUnavailable }
        assertNonBlankForEveryLanguage("voiceBusy") { voiceBusy }
        assertNonBlankForEveryLanguage("voiceGenericError") { voiceGenericError }
        assertNonBlankForEveryLanguage("voiceNotAvailable") { voiceNotAvailable }
        assertNonBlankForEveryLanguage("voiceStartFailed") { voiceStartFailed }
        for (lang in SupportedLanguage.entries) {
            assertFalse(
                "${lang.englishName} freeDocumentLimitReached(1) must be non-blank",
                lang.freeDocumentLimitReached(1).isBlank(),
            )
            assertTrue(
                "${lang.englishName} freeDocumentLimitReached(3) must mention the count",
                lang.freeDocumentLimitReached(3).contains("3"),
            )
        }
    }

    @Test
    fun non_Hindi_non_Marathi_languages_do_not_silently_fall_back_to_English() {
        // The exact bug: Telugu (and every language except Hindi/Marathi) was
        // getting the English string via a hidden `else` branch. Assert a
        // representative non-Hindi/Marathi language actually differs from
        // English for each property that was affected.
        val affected = listOf(SupportedLanguage.TELUGU, SupportedLanguage.TAMIL, SupportedLanguage.BENGALI)
        for (lang in affected) {
            assertNotEquals("${lang.englishName} notifPermTitle", SupportedLanguage.ENGLISH.notifPermTitle, lang.notifPermTitle)
            assertNotEquals("${lang.englishName} batteryOptExplanation", SupportedLanguage.ENGLISH.batteryOptExplanation, lang.batteryOptExplanation)
            assertNotEquals("${lang.englishName} allowLabel", SupportedLanguage.ENGLISH.allowLabel, lang.allowLabel)
            assertNotEquals("${lang.englishName} notNowLabel", SupportedLanguage.ENGLISH.notNowLabel, lang.notNowLabel)
        }
    }

    // ── Home quick-action chips ───────────────────────────────────────────

    @Test
    fun every_language_has_exactly_three_home_quick_actions_for_both_tiers() {
        for (lang in SupportedLanguage.entries) {
            assertEquals("${lang.englishName} homeQuickActions", 3, lang.homeQuickActions.size)
            assertEquals("${lang.englishName} homeQuickActionsCompact", 3, lang.homeQuickActionsCompact.size)
            for (chip in lang.homeQuickActions + lang.homeQuickActionsCompact) {
                assertFalse("${lang.englishName} has a blank quick-action chip", chip.isBlank())
            }
        }
    }

    @Test
    fun compact_quick_actions_never_offer_PDF_summarize_or_Kisan() {
        // Compact (1B) can't attach documents and its own catalog description
        // disclaims Kisan-pack support ("not for knowledge packs like Kisan") —
        // the Compact chip set must never reintroduce either.
        for (lang in SupportedLanguage.entries) {
            val chips = lang.homeQuickActionsCompact.joinToString(" ")
            assertFalse(
                "${lang.englishName} Compact quick actions must not mention PDF",
                chips.contains("PDF", ignoreCase = true),
            )
            assertFalse(
                "${lang.englishName} Compact quick actions must not mention Kisan",
                chips.contains("Kisan", ignoreCase = true),
            )
        }
    }
}
