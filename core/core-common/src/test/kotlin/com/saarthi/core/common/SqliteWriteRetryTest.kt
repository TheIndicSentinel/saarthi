package com.saarthi.core.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SqliteWriteRetryTest {

    @Test
    fun `malformed disk image is unusable`() {
        assertTrue(isSqliteUnusable(RuntimeException("database disk image is malformed")))
    }

    @Test
    fun `disk full is unusable`() {
        assertTrue(isSqliteUnusable(RuntimeException("SQLITE_FULL: disk is full")))
    }

    @Test
    fun `ordinary errors are not unusable`() {
        assertFalse(isSqliteUnusable(IllegalStateException("UNIQUE constraint failed")))
        assertFalse(isSqliteUnusable(RuntimeException("network timeout")))
    }

    @Test
    fun `cause chain is inspected`() {
        val nested = RuntimeException("wrapper", RuntimeException("database disk image is malformed"))
        assertTrue(isSqliteUnusable(nested))
    }

    @Test
    fun `succeeds on first try`() = runBlocking {
        var calls = 0
        val result = sqliteWriteWithRetry {
            calls += 1
            7
        }
        assertEquals(7, result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries once then succeeds`() = runBlocking {
        var calls = 0
        val result = sqliteWriteWithRetry {
            calls += 1
            if (calls == 1) error("database disk image is malformed")
            42
        }
        assertEquals(42, result)
        assertEquals(2, calls)
    }

    @Test
    fun `second failure becomes SqliteUnusableException`() = runBlocking {
        try {
            sqliteWriteWithRetry<Unit> {
                error("database disk image is malformed")
            }
            fail("expected SqliteUnusableException")
        } catch (e: SqliteUnusableException) {
            assertEquals(SqliteUnusableException.USER_MESSAGE, e.message)
        }
    }

    @Test
    fun `non-sqlite failure is not retried`() = runBlocking {
        var calls = 0
        try {
            sqliteWriteWithRetry<Unit> {
                calls += 1
                error("UNIQUE constraint failed")
            }
            fail("expected original error")
        } catch (e: IllegalStateException) {
            assertEquals(1, calls)
            assertTrue(e.message!!.contains("UNIQUE"))
        }
    }
}
