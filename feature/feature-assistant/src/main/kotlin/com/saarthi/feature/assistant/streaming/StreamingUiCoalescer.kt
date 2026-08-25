package com.saarthi.feature.assistant.streaming

/**
 * Batches high-frequency streaming updates so state holders and Compose are not
 * hammered on every native token (~10–50/sec on low-end devices).
 *
 * Pair with plain-text rendering in [com.saarthi.feature.assistant.ui.components.MessageBubble]
 * while `isStreaming` — full markdown runs once when the turn completes.
 */
class StreamingUiCoalescer(
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var lastFlushMs = 0L

    /**
     * @return `true` when [onFlush] was invoked (caller may skip other work).
     */
    fun onToken(visibleText: String, onFlush: (String) -> Unit): Boolean {
        val now = clock()
        if (now - lastFlushMs >= flushIntervalMs) {
            lastFlushMs = now
            onFlush(visibleText)
            return true
        }
        return false
    }

    /** Force a flush — e.g. stream end when the last token landed inside the interval. */
    fun flushNow(visibleText: String, onFlush: (String) -> Unit) {
        lastFlushMs = clock()
        onFlush(visibleText)
    }

    fun reset() {
        lastFlushMs = 0L
    }

    companion object {
        /** ~12 UI updates/sec — below human-perceptible lag, ~10× fewer than per-token. */
        const val DEFAULT_FLUSH_INTERVAL_MS = 80L
    }
}
