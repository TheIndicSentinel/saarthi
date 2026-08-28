package com.saarthi.feature.assistant.data

import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.LogPrivacy
import com.saarthi.core.memory.domain.MemoryRepository

/**
 * Write-time persistence policy for one memory fact: reject junk aggregate
 * keys, route durable identity facts to [MemoryRepository.USER_SCOPE], guard
 * name shape/completeness, and merge list-type values.
 *
 * Extracted from ChatRepositoryImpl so marker-based and implicit extraction
 * share one policy without that class owning the rules. Field-constructed
 * from existing ChatRepositoryImpl constructor deps — no extra Hilt binding.
 */
internal class MemoryFactWriter(
    private val memoryRepository: MemoryRepository,
    private val implicitFactExtractor: ImplicitFactExtractor,
) {
    // Model-authored aggregate keys that bundle several facts into one value.
    // Every real fact already lands under its own key, so these are pure
    // duplication/noise. Rejected at write time. Stays here (not in
    // ImplicitFactExtractor) — it's a write-time persistence policy, not
    // an extraction pattern; nothing in that class references it.
    private val JUNK_AGGREGATE_KEYS = setOf(
        "user_facts", "facts", "user_info", "info", "user_details", "details",
        "about", "about_user", "about_me", "profile", "user_profile",
        "summary", "notes", "user_data", "data",
    )

    /**
     * Persist one memory fact, routing it to the correct tier:
     *  • durable identity facts (name, city, profession, …) → USER_SCOPE so
     *    they follow the user into every future chat (cross-session profile);
     *  • everything else → the supplied [sessionId] (per-chat context).
     * Centralised so marker-based and implicit extraction share one policy.
     */
    suspend fun persist(sessionId: String, rawKey: String, value: String) {
        val key = rawKey.trim().lowercase().replace(" ", "_")
        val v = value.trim()
        if (key.isBlank() || v.isBlank()) return
        // Reject model-authored AGGREGATE keys ("user_facts" with a value like
        // "नाम: अर्जुन, राशि: धनु") — they duplicate facts that already have
        // proper keys, bloat prompt injection, and read as junk in the
        // Knowledge screen (field log 2026-07-03).
        if (key in JUNK_AGGREGATE_KEYS) {
            DebugLogger.log("MEMORY", "write REJECTED (aggregate key) ${LogPrivacy.keyLen(key)}")
            return
        }
        val target =
            if (MemoryRepository.isUserScopedKey(key)) MemoryRepository.USER_SCOPE else sessionId
        // NAME guard: small models emit [SAARTHI_MEMORY] markers with truncated /
        // garbled names (e.g. the 2-char Devanagari "अर" for "अर्जुन") OR whole
        // sentences ("उपयोगकर्ता का नाम अर्जुन है" = "the user's name is Arjun")
        // that would clobber the high-precision implicit-extracted name via
        // set()'s upsert — and that wrong value then drives the home greeting.
        // Two-layer defence:
        //  1. SHAPE gate: a plausible name value is 1–3 tokens with no
        //     sentence punctuation. Sentence-shaped values never enter a name
        //     key at all (the earlier length-only rule let a LONGER garbled
        //     sentence overwrite a clean short name).
        //  2. COMPLETENESS gate: among plausible values, never replace an
        //     existing name with a shorter one.
        val isNameKey = MemoryRepository.isNameKey(key)
        if (isNameKey) {
            if (!implicitFactExtractor.isPlausibleNameValue(v)) {
                DebugLogger.log("MEMORY", "name write REJECTED (shape) ${LogPrivacy.keyLen(key)} ${LogPrivacy.valueLen(v)}")
                return
            }
            val existing = memoryRepository.get(sessionId = target, key = key)?.value?.trim()
            if (!existing.isNullOrBlank() && existing.length >= v.length) {
                DebugLogger.log("MEMORY", "name write SKIPPED (existing more complete) ${LogPrivacy.keyLen(key)}")
                return
            }
        }
        // File-visible write trail — the forensic record for any "memory feels
        // wrong" report: what key was written and to which scope. No value
        // content (memory values can be PII, e.g. names) — length only.
        DebugLogger.log(
            "MEMORY",
            "write ${LogPrivacy.keyLen(key)} scope=${if (target == MemoryRepository.USER_SCOPE) "USER" else "session"} ${LogPrivacy.valueLen(v)}",
        )
        // List-type facts ACCUMULATE instead of overwrite: "I like apples" then
        // "I like oranges" → both kept (dedup, capped, oldest dropped). Single
        // identity facts (name, age, city, diet…) still override, as before.
        if (!isNameKey && MemoryRepository.isListKey(key)) {
            val existing = memoryRepository.get(sessionId = target, key = key)?.value
            val merged = MemoryRepository.mergeListValue(existing, v)
            memoryRepository.set(sessionId = target, key = key, value = merged, packSource = "USER")
            return
        }
        memoryRepository.set(sessionId = target, key = key, value = v, packSource = "USER")
    }
}
