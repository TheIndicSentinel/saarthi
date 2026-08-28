package com.saarthi.core.inference

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the in-memory ring buffer: bounded at [InferenceMetricsRecorder.MAX_RECORDED_TURNS],
 * oldest-evicted, snapshot is a copy, completed true/false preserved. No user text
 * is part of [InferenceTurnMetrics] — only catalog/file name + numeric timings.
 */
class InferenceMetricsRecorderTest {

    @Before
    fun setUp() {
        InferenceMetricsRecorder.clear()
    }

    @After
    fun tearDown() {
        InferenceMetricsRecorder.clear()
    }

    @Test
    fun `records up to 32 then drops oldest`() {
        assertEquals(32, InferenceMetricsRecorder.MAX_RECORDED_TURNS)
        repeat(InferenceMetricsRecorder.MAX_RECORDED_TURNS) { i ->
            InferenceMetricsRecorder.record(turn(modelId = "m$i", tokenCount = i))
        }
        val snap = InferenceMetricsRecorder.snapshot()
        assertEquals(32, snap.size)
        assertEquals("m0", snap.first().modelId)
        assertEquals("m31", snap.last().modelId)
        assertEquals(0, snap.first().tokenCount)
        assertEquals(31, snap.last().tokenCount)
    }

    @Test
    fun `does not grow unbounded — 40 records keep last 32 and evict the first`() {
        repeat(40) { i ->
            InferenceMetricsRecorder.record(turn(modelId = "m$i", tokenCount = i))
        }
        val snap = InferenceMetricsRecorder.snapshot()
        assertEquals(32, snap.size)
        assertEquals("m8", snap.first().modelId)
        assertEquals(8, snap.first().tokenCount)
        assertEquals("m39", snap.last().modelId)
        assertEquals(39, snap.last().tokenCount)
        assertFalse(snap.any { it.modelId == "m0" })
    }

    @Test
    fun `snapshot is a copy — mutating it does not affect the recorder`() {
        InferenceMetricsRecorder.record(turn(modelId = "keep"))
        val snap = InferenceMetricsRecorder.snapshot().toMutableList()
        assertEquals(1, snap.size)
        snap.clear()
        snap.add(turn(modelId = "injected"))
        val again = InferenceMetricsRecorder.snapshot()
        assertEquals(1, again.size)
        assertEquals("keep", again.single().modelId)
        assertNotSame(snap, again)
    }

    @Test
    fun `completed true and false are preserved`() {
        InferenceMetricsRecorder.record(turn(modelId = "ok", completed = true))
        InferenceMetricsRecorder.record(turn(modelId = "cancel", completed = false))
        val snap = InferenceMetricsRecorder.snapshot()
        assertEquals(2, snap.size)
        assertTrue(snap[0].completed)
        assertFalse(snap[1].completed)
        assertEquals("ok", snap[0].modelId)
        assertEquals("cancel", snap[1].modelId)
    }

    @Test
    fun `snapshot is oldest to newest`() {
        InferenceMetricsRecorder.record(turn(modelId = "first", elapsedMs = 10L))
        InferenceMetricsRecorder.record(turn(modelId = "second", elapsedMs = 20L))
        InferenceMetricsRecorder.record(turn(modelId = "third", elapsedMs = 30L))
        assertEquals(
            listOf("first", "second", "third"),
            InferenceMetricsRecorder.snapshot().map { it.modelId },
        )
    }

    private fun turn(
        modelId: String,
        backend: String = "CPU",
        tokenCount: Int = 1,
        elapsedMs: Long = 100L,
        ttftMs: Long = 40L,
        tps: Float = 10f,
        decodeTps: Float = 12f,
        completed: Boolean = true,
    ) = InferenceTurnMetrics(
        modelId = modelId,
        backend = backend,
        tokenCount = tokenCount,
        elapsedMs = elapsedMs,
        ttftMs = ttftMs,
        tps = tps,
        decodeTps = decodeTps,
        completed = completed,
    )
}
