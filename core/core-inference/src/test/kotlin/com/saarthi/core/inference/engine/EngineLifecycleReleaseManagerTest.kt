package com.saarthi.core.inference.engine

import android.app.Activity
import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [EngineLifecycleReleaseManager] is the debounced two-stage
 * background-release scheduling extracted out of [LiteRTInferenceEngine]
 * as part of the C3 God-class reduction — its ActivityLifecycleCallbacks
 * and delay logic get direct test coverage here for the first time
 * (previously only exercisable by backgrounding a real device).
 *
 * The class's `lifecycleScope` uses `Dispatchers.Main.immediate`, so every
 * test routes `Dispatchers.setMain` through the SAME [TestCoroutineScheduler]
 * the test's own `runTest` uses — otherwise `advanceTimeBy` below would
 * advance a clock the manager's internal delay() calls aren't listening to.
 */
class EngineLifecycleReleaseManagerTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manager(
        application: Application,
        isNativeGenerating: () -> Boolean = { false },
        isInitInProgress: () -> Boolean = { false },
        onReleaseConversation: () -> Unit = {},
        onReleaseEngine: () -> Unit = {},
    ) = EngineLifecycleReleaseManager(
        context = application,
        isNativeGenerating = isNativeGenerating,
        isInitInProgress = isInitInProgress,
        releaseConversationOnly = { onReleaseConversation() },
        releaseEngine = { onReleaseEngine() },
    )

    private fun mockApplication(): Pair<Application, io.mockk.CapturingSlot<Application.ActivityLifecycleCallbacks>> {
        val app = mockk<Application>(relaxed = true)
        val slot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(slot)) } returns Unit
        return app to slot
    }

    @Test
    fun `register() on a real Application registers the lifecycle callback`() {
        val (app, slot) = mockApplication()
        manager(app).register()
        verify(exactly = 1) { app.registerActivityLifecycleCallbacks(any()) }
        assert(slot.isCaptured)
    }

    @Test
    fun `register() on a plain non-Application Context is a silent no-op`() {
        // Mirrors the original inline `(context as? Application)?...` exactly
        // — a Context that isn't really an Application must not throw or
        // register anything.
        val plainContext = mockk<Context>(relaxed = true)
        val mgr = EngineLifecycleReleaseManager(
            context = plainContext,
            isNativeGenerating = { false },
            isInitInProgress = { false },
            releaseConversationOnly = {},
            releaseEngine = {},
        )
        mgr.register() // must not throw
    }

    @Test
    fun `backgrounding past both delays releases the conversation then the engine`() = runTest(testDispatcher) {
        val (app, slot) = mockApplication()
        var conversationReleased = false
        var engineReleased = false
        val mgr = manager(
            app,
            onReleaseConversation = { conversationReleased = true },
            onReleaseEngine = { engineReleased = true },
        )
        mgr.register()
        val callbacks = slot.captured

        callbacks.onActivityStarted(mockk<Activity>())
        callbacks.onActivityStopped(mockk<Activity>())

        // Stage 1 (60s): conversation released, engine not yet.
        scheduler.advanceTimeBy(61_000)
        scheduler.runCurrent()
        assertEquals(true, conversationReleased)
        assertEquals(false, engineReleased)

        // Stage 2 (120s total): engine now released too.
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(true, engineReleased)
    }

    @Test
    fun `returning to foreground before the delay elapses cancels the pending release`() = runTest(testDispatcher) {
        val (app, slot) = mockApplication()
        var conversationReleased = false
        val mgr = manager(app, onReleaseConversation = { conversationReleased = true })
        mgr.register()
        val callbacks = slot.captured

        callbacks.onActivityStarted(mockk<Activity>())
        callbacks.onActivityStopped(mockk<Activity>())
        scheduler.advanceTimeBy(30_000) // well before the 60s stage-1 delay
        scheduler.runCurrent()

        callbacks.onActivityStarted(mockk<Activity>()) // back to foreground — cancels pending release
        scheduler.advanceTimeBy(60_000) // past where stage 1 would have fired
        scheduler.runCurrent()

        assertEquals(false, conversationReleased)
    }

    @Test
    fun `an in-flight generation blocks the scheduled release even after the delay elapses`() = runTest(testDispatcher) {
        val (app, slot) = mockApplication()
        var conversationReleased = false
        val mgr = manager(
            app,
            isNativeGenerating = { true }, // generation still running
            onReleaseConversation = { conversationReleased = true },
        )
        mgr.register()
        val callbacks = slot.captured

        callbacks.onActivityStarted(mockk<Activity>())
        callbacks.onActivityStopped(mockk<Activity>())
        scheduler.advanceTimeBy(61_000)
        scheduler.runCurrent()

        assertEquals(false, conversationReleased)
    }

    @Test
    fun `an in-flight model load blocks the scheduled release even after the delay elapses`() = runTest(testDispatcher) {
        val (app, slot) = mockApplication()
        var conversationReleased = false
        val mgr = manager(
            app,
            isInitInProgress = { true }, // initMutex held
            onReleaseConversation = { conversationReleased = true },
        )
        mgr.register()
        val callbacks = slot.captured

        callbacks.onActivityStarted(mockk<Activity>())
        callbacks.onActivityStopped(mockk<Activity>())
        scheduler.advanceTimeBy(61_000)
        scheduler.runCurrent()

        assertEquals(false, conversationReleased)
    }
}
