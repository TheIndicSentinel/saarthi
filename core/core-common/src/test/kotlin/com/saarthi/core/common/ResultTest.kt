package com.saarthi.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Result<T> is the error-transport used everywhere in the domain layer.
 * A silent operator bug — e.g. map() losing errors — would corrupt state
 * across the whole app with no visible exception at the call site.
 */
class ResultTest {

    // ── onSuccess ──────────────────────────────────────────────────────────────

    @Test
    fun `onSuccess fires only for Success`() {
        var called = false
        Result.Success("data").onSuccess { called = true }
        assertTrue("onSuccess must fire for Success", called)
    }

    @Test
    fun `onSuccess does not fire for Error`() {
        var called = false
        Result.Error(RuntimeException()).onSuccess { called = true }
        if (called) fail("onSuccess must not fire for Error")
    }

    @Test
    fun `onSuccess does not fire for Loading`() {
        var called = false
        Result.Loading.onSuccess { called = true }
        if (called) fail("onSuccess must not fire for Loading")
    }

    // ── onError ────────────────────────────────────────────────────────────────

    @Test
    fun `onError fires only for Error`() {
        var called = false
        Result.Error(RuntimeException("boom")).onError { _, _ -> called = true }
        assertTrue("onError must fire for Error", called)
    }

    @Test
    fun `onError does not fire for Success`() {
        var called = false
        Result.Success("ok").onError { _, _ -> called = true }
        if (called) fail("onError must not fire for Success")
    }

    @Test
    fun `onError does not fire for Loading`() {
        var called = false
        Result.Loading.onError { _, _ -> called = true }
        if (called) fail("onError must not fire for Loading")
    }

    // ── onLoading ──────────────────────────────────────────────────────────────

    @Test
    fun `onLoading fires only for Loading`() {
        var called = false
        Result.Loading.onLoading { called = true }
        assertTrue("onLoading must fire for Loading", called)
    }

    @Test
    fun `onLoading does not fire for Success or Error`() {
        var called = false
        Result.Success(1).onLoading { called = true }
        Result.Error(RuntimeException()).onLoading { called = true }
        if (called) fail("onLoading must not fire for Success or Error")
    }

    // ── map ────────────────────────────────────────────────────────────────────

    @Test
    fun `map transforms Success value`() {
        val result = Result.Success(5).map { it * 2 }
        assertTrue(result is Result.Success)
        assertEquals(10, (result as Result.Success).data)
    }

    @Test
    fun `map passes Error through unchanged`() {
        val exception = RuntimeException("original")
        val original: Result<Int> = Result.Error(exception, "msg")
        val mapped = original.map { it * 2 }
        assertTrue(mapped is Result.Error)
        assertSame("map must not wrap or replace the original exception",
            exception, (mapped as Result.Error).exception)
    }

    @Test
    fun `map passes Loading through unchanged`() {
        val result: Result<Int> = Result.Loading
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Loading)
    }

    // ── Error fields ───────────────────────────────────────────────────────────

    @Test
    fun `Error preserves exception and optional message`() {
        val cause = IllegalStateException("cause")
        val withMsg = Result.Error(cause, "human message")
        assertSame(cause, withMsg.exception)
        assertEquals("human message", withMsg.message)

        val noMsg = Result.Error(cause)
        assertNull(noMsg.message)
    }
}
