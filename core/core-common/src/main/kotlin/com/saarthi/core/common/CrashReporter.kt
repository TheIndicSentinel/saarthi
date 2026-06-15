package com.saarthi.core.common

/**
 * Crash + diagnostic event sink.
 *
 * [LocalCrashReporter] is the only implementation: everything is written to the
 * on-device `saarthi_debug.log` and NEVER leaves the phone. Saarthi ships no
 * Firebase / Crashlytics / Analytics and sends no telemetry to any server — a
 * hard part of the offline-first, no-data-on-server promise.
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

/**
 * Default implementation: write everything to the on-device debug log via
 * [com.saarthi.core.inference.DebugLogger] without ever leaving the device.
 *
 * Loaded reflectively to avoid a hard dependency from core-common back into
 * core-inference (DebugLogger lives in core-inference).
 */
class LocalCrashReporter : CrashReporter {

    private val debugLogger = runCatching {
        Class.forName("com.saarthi.core.inference.DebugLogger")
    }.getOrNull()

    private fun logTo(tag: String, message: String) {
        runCatching {
            val m = debugLogger?.getMethod("log", String::class.java, String::class.java)
            m?.invoke(null, tag, message)
        }
    }

    override fun recordException(throwable: Throwable, tags: Map<String, String>) {
        logTo("CRASH", "non-fatal: ${throwable.javaClass.name}: ${throwable.message}")
        tags.forEach { (k, v) -> logTo("CRASH", "  $k=$v") }
        throwable.stackTrace.take(8).forEach { frame ->
            logTo("CRASH", "  at $frame")
        }
    }

    override fun log(tag: String, message: String) = logTo(tag, message)

    override fun setUserId(id: String) = logTo("CRASH", "userId=$id")

    override fun setKey(key: String, value: String) = logTo("CRASH", "$key=$value")
}
