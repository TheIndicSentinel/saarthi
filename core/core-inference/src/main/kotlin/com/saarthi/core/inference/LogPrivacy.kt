package com.saarthi.core.inference

/**
 * Point 9 — privacy helpers for [DebugLogger] / Timber messages that may be
 * attached via Support or (in beta) land in public Downloads.
 *
 * Policy: for **user-sourced** strings (document names, memory keys, reminder
 * text, free-form paths under the user's control) log **lengths / counts /
 * booleans** only — never the raw value. Catalog model ids / SHA diagnostics
 * and hardware facts (SoC, API) remain OK.
 *
 * Call sites should prefer these helpers over interpolating raw names into
 * log lines. This object does not rewrite existing messages; it only formats
 * safe fragments so the contract is unit-testable and greppable.
 */
object LogPrivacy {

    /** e.g. `nameLen=12` for a user document display name or file name. */
    fun nameLen(name: String): String = "nameLen=${name.length}"

    /** e.g. `keyLen=8` for a memory key (may contain personal labels). */
    fun keyLen(key: String): String = "keyLen=${key.length}"

    /** e.g. `sessionIdLen=36` — ids are correlators; length is enough to debug mismatches. */
    fun sessionIdLen(sessionId: String): String = "sessionIdLen=${sessionId.length}"

    /** e.g. `valueLen=24` when a crash-tag value must not be echoed. */
    fun valueLen(value: String): String = "valueLen=${value.length}"
}
