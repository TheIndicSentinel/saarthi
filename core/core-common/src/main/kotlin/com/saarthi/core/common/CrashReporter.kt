package com.saarthi.core.common

/**
 * Crash + diagnostic event sink.
 *
 * The only implementation ([com.saarthi.core.inference.LocalCrashReporter])
 * writes everything to the on-device `saarthi_debug.log` and NEVER leaves the
 * phone. Saarthi ships no Firebase / Crashlytics / Analytics and sends no
 * telemetry to any server — a hard part of the offline-first, no-data-on-
 * server promise.
 *
 * The interface lives here (core-common) so any module can depend on and
 * inject it without needing core-inference on its compile classpath; the
 * implementation lives in core-inference — see that class's kdoc for why
 * (point 6: it used to bridge back here via reflection instead).
 */
interface CrashReporter {
    /** Record a non-fatal throwable + optional context tags. */
    fun recordException(throwable: Throwable, tags: Map<String, String> = emptyMap())

    /** Breadcrumb-style log line, kept with the next crash. */
    fun log(tag: String, message: String)

    /** Stable user-scoped identifier (anonymous; not the user's name). */
    fun setUserId(id: String)

    /** Custom key/value attached to every subsequent event. */
    fun setKey(key: String, value: String)
}
