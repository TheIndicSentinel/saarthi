package com.saarthi.core.inference

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 8: [DebugLogger.log] only enqueues; process death can drop CRASH
 * lines still in the channel. [DebugLogger.flushBlocking] waits (with a
 * timeout) until already-enqueued lines are on the file sink.
 */
class DebugLoggerFlushTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `flushBlocking persists an enqueued line on the file sink`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        DebugLogger.bindFileSinkForTests(log)
        val marker = "flush-marker-${System.nanoTime()}"
        DebugLogger.log("TEST", marker)
        DebugLogger.flushBlocking(timeoutMs = 1_000)
        assertTrue(
            "Flushed log must contain the enqueued marker. Got:\n${log.readText()}",
            log.readText().contains(marker),
        )
    }

    @Test
    fun `flushBlocking returns quickly when idle and does not hang`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        DebugLogger.bindFileSinkForTests(log)
        val start = System.currentTimeMillis()
        DebugLogger.flushBlocking(timeoutMs = 500)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Idle flush must not consume the timeout. elapsed=${elapsed}ms", elapsed < 200)
    }

    @Test
    fun `flushBlocking with zero timeout returns without hanging`() {
        val start = System.currentTimeMillis()
        DebugLogger.flushBlocking(timeoutMs = 0)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Zero-timeout flush must return immediately. elapsed=${elapsed}ms", elapsed < 200)
    }
}
