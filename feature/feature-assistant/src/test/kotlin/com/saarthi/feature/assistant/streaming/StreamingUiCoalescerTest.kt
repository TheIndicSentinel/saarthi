package com.saarthi.feature.assistant.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingUiCoalescerTest {

    @Test
    fun `first token flushes immediately`() {
        var now = 1_000L
        val flushed = mutableListOf<String>()
        val coalescer = StreamingUiCoalescer(clock = { now })

        assertTrue(coalescer.onToken("a") { flushed += it })

        assertEquals(listOf("a"), flushed)
    }

    @Test
    fun `tokens inside interval are coalesced`() {
        var now = 1_000L
        val flushed = mutableListOf<String>()
        val coalescer = StreamingUiCoalescer(flushIntervalMs = 80L, clock = { now })

        coalescer.onToken("a") { flushed += it }
        now += 10
        assertFalse(coalescer.onToken("ab") { flushed += it })
        now += 10
        assertFalse(coalescer.onToken("abc") { flushed += it })
        now += 70 // 90ms since first flush
        assertTrue(coalescer.onToken("abcd") { flushed += it })

        assertEquals(listOf("a", "abcd"), flushed)
    }

    @Test
    fun `flushNow always publishes latest text`() {
        var now = 1_000L
        val flushed = mutableListOf<String>()
        val coalescer = StreamingUiCoalescer(flushIntervalMs = 80L, clock = { now })

        coalescer.onToken("partial") { flushed += it }
        now += 5
        coalescer.flushNow("final") { flushed += it }

        assertEquals(listOf("partial", "final"), flushed)
    }

    @Test
    fun `reset allows immediate flush on next token`() {
        var now = 1_000L
        val flushed = mutableListOf<String>()
        val coalescer = StreamingUiCoalescer(flushIntervalMs = 80L, clock = { now })

        coalescer.onToken("one") { flushed += it }
        now += 5
        coalescer.reset()
        assertTrue(coalescer.onToken("two") { flushed += it })

        assertEquals(listOf("one", "two"), flushed)
    }
}
