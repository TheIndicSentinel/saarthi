package com.saarthi.core.inference

/**
 * Structured per-turn inference metrics. In-memory only — never persisted,
 * never sent off-device, and never includes prompt or response text.
 *
 * Complements the existing prose `LITERT` "Stream done" / "Stream cancelled"
 * log lines with a queryable in-process record (last [InferenceMetricsRecorder.MAX_RECORDED_TURNS]
 * turns) so latency/token numbers are not only greppable prose.
 */
data class InferenceTurnMetrics(
    /** Catalog id or model file name — never user text. */
    val modelId: String,
    /** Value of [com.saarthi.core.inference.engine.LiteRTInferenceEngine] `backendLabel()`. */
    val backend: String,
    val tokenCount: Int,
    val elapsedMs: Long,
    /** Time-to-first-token in ms; −1 if unknown (cancelled before first token). */
    val ttftMs: Long,
    val tps: Float,
    val decodeTps: Float,
    /** false if the turn was cancelled or failed. */
    val completed: Boolean,
)

/**
 * Tiny process-local ring buffer of recent inference turns.
 *
 * A plain `object` (not Hilt) so [com.saarthi.core.inference.engine.LiteRTInferenceEngine]
 * can record from native callback threads without injection, and unit tests
 * can drive it with no Robolectric/Android runtime.
 *
 * [record] never throws and never stores prompt/response text — only the
 * fields on [InferenceTurnMetrics].
 */
object InferenceMetricsRecorder {

    const val MAX_RECORDED_TURNS = 32

    private val lock = Any()
    private val buffer = ArrayDeque<InferenceTurnMetrics>(MAX_RECORDED_TURNS)

    fun record(turn: InferenceTurnMetrics) {
        try {
            synchronized(lock) {
                if (buffer.size >= MAX_RECORDED_TURNS) {
                    buffer.removeFirst()
                }
                buffer.addLast(turn)
            }
        } catch (_: Throwable) {
            // Metrics must never affect inference.
        }
    }

    /** Oldest-to-newest copy. Mutating the returned list does not affect the recorder. */
    fun snapshot(): List<InferenceTurnMetrics> = try {
        synchronized(lock) { buffer.toList() }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Test/debug only — drops the in-memory buffer. */
    fun clear() {
        try {
            synchronized(lock) { buffer.clear() }
        } catch (_: Throwable) {
            // Metrics must never affect inference.
        }
    }
}
