package com.saarthi.core.common

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UseCase / FlowUseCase / NoParamUseCase are used by every feature's domain layer.
 * If withContext dispatches on the wrong thread or exceptions aren't caught correctly,
 * it's a systemic failure across all features.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UseCaseTest {

    // ── UseCase (single-shot) ──────────────────────────────────────────────────

    @Test
    fun `UseCase returns Success for successful execute`() = runTest {
        val useCase = object : UseCase<String, Int>(UnconfinedTestDispatcher()) {
            override suspend fun execute(params: String): Int = params.length
        }

        val result = useCase("hello")

        assertTrue(result is Result.Success)
        assertEquals(5, (result as Result.Success).data)
    }

    @Test
    fun `UseCase returns Error when execute throws`() = runTest {
        val boom = RuntimeException("deliberate failure")
        val useCase = object : UseCase<Unit, String>(UnconfinedTestDispatcher()) {
            override suspend fun execute(params: Unit): String = throw boom
        }

        val result = useCase(Unit)

        assertTrue(result is Result.Error)
        assertEquals(boom, (result as Result.Error).exception)
    }

    @Test
    fun `UseCase runs on the injected dispatcher`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var executed = false

        val useCase = object : UseCase<Unit, Unit>(testDispatcher) {
            override suspend fun execute(params: Unit) { executed = true }
        }

        // Enqueue the call — StandardTestDispatcher does NOT run eagerly
        val deferred = kotlinx.coroutines.async { useCase(Unit) }

        // Nothing has run yet because StandardTestDispatcher is not unconfined
        assertTrue("Should not have run before scheduler advance", !executed)

        // Drain the scheduler — now the withContext(testDispatcher) block executes
        testScheduler.advanceUntilIdle()
        deferred.await()

        assertTrue("Must have executed after scheduler advance", executed)
    }

    // ── FlowUseCase ────────────────────────────────────────────────────────────

    @Test
    fun `FlowUseCase emits Error when flow throws`() = runTest {
        val boom = RuntimeException("stream error")
        val useCase = object : FlowUseCase<Unit, String>(UnconfinedTestDispatcher()) {
            override fun execute(params: Unit): Flow<Result<String>> = flow { throw boom }
        }

        useCase(Unit).test {
            val item = awaitItem()
            assertTrue(item is Result.Error)
            assertEquals(boom, (item as Result.Error).exception)
            awaitComplete()
        }
    }

    @Test
    fun `FlowUseCase emits Success values from upstream flow`() = runTest {
        val useCase = object : FlowUseCase<Unit, Int>(UnconfinedTestDispatcher()) {
            override fun execute(params: Unit): Flow<Result<Int>> =
                flowOf(Result.Success(1), Result.Success(2), Result.Success(3))
        }

        useCase(Unit).test {
            assertEquals(1, (awaitItem() as Result.Success).data)
            assertEquals(2, (awaitItem() as Result.Success).data)
            assertEquals(3, (awaitItem() as Result.Success).data)
            awaitComplete()
        }
    }

    // ── NoParamUseCase ─────────────────────────────────────────────────────────

    @Test
    fun `NoParamUseCase returns Success without params`() = runTest {
        val useCase = object : NoParamUseCase<String>(UnconfinedTestDispatcher()) {
            override suspend fun execute(): String = "pong"
        }

        val result = useCase()

        assertTrue(result is Result.Success)
        assertEquals("pong", (result as Result.Success).data)
    }

    @Test
    fun `NoParamUseCase returns Error when execute throws`() = runTest {
        val bang = IllegalStateException("no-param boom")
        val useCase = object : NoParamUseCase<Unit>(UnconfinedTestDispatcher()) {
            override suspend fun execute(): Unit = throw bang
        }

        val result = useCase()

        assertTrue(result is Result.Error)
        assertEquals(bang, (result as Result.Error).exception)
    }
}
