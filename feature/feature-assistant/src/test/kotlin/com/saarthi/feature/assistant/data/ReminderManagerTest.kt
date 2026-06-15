package com.saarthi.feature.assistant.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ReminderManager schedules time-sensitive notifications — a broken reminder
 * silently fails, and the user misses their medication, meeting, or alert.
 * These tests verify the scheduling contract against the Android AlarmManager
 * without needing a real device or emulator.
 *
 * Android system-service calls (AlarmManager, NotificationManager, PendingIntent)
 * are all mocked. Build.VERSION.SDK_INT defaults to 0 in JVM tests (< S), so
 * canScheduleExact() returns true and setExactAndAllowWhileIdle() is the
 * verified scheduling path; the inexact branch is the Android 13+ fallback.
 */
class ReminderManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockAlarmManager: AlarmManager
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockPendingIntent: PendingIntent
    private lateinit var manager: ReminderManager

    @Before
    fun setUp() {
        mockkStatic(PendingIntent::class)

        mockAlarmManager = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        mockPendingIntent = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        every { mockContext.packageName } returns "com.saarthi.app"
        every { mockContext.getSystemService(Context.ALARM_SERVICE) } returns mockAlarmManager
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        every {
            PendingIntent.getBroadcast(any(), any(), any(), any())
        } returns mockPendingIntent

        manager = ReminderManager(mockContext)
    }

    @After
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
    }

    // ── scheduleByDelay ────────────────────────────────────────────────────────

    @Test
    fun `scheduleByDelay returns true and calls setExactAndAllowWhileIdle`() {
        val result = manager.scheduleByDelay("dinner", 30)

        assertTrue("scheduleByDelay must return true on success", result)
        verify { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), mockPendingIntent) }
    }

    @Test
    fun `scheduleByDelay returns false when AlarmManager throws`() {
        every {
            mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any())
        } throws SecurityException("no exact alarms")

        val result = manager.scheduleByDelay("wake up", 10)

        assertFalse("Must return false on AlarmManager exception", result)
    }

    // ── scheduleReminder (HH:MM absolute time) ─────────────────────────────────

    @Test
    fun `scheduleReminder with valid time calls AlarmManager and returns true`() {
        val result = manager.scheduleReminder("meeting", "14:00")

        assertTrue("scheduleReminder must return true for valid time", result)
        verify { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), mockPendingIntent) }
    }

    @Test
    fun `scheduleReminder with invalid format returns false without calling AlarmManager`() {
        val result = manager.scheduleReminder("meeting", "not-a-time")

        assertFalse("Must return false for unparseable time string", result)
        verify(exactly = 0) { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `scheduleReminder with missing minutes returns false`() {
        val result = manager.scheduleReminder("alarm", "14")

        assertFalse("Single-part time string must fail gracefully", result)
        verify(exactly = 0) { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    // ── Notification channel ───────────────────────────────────────────────────

    @Test
    fun `createNotificationChannel is a no-op when SDK_INT is below O`() {
        // Build.VERSION.SDK_INT returns 0 in JVM unit tests, which is < O (26).
        // Calling createNotificationChannel() explicitly must not crash and must
        // not touch the NotificationManager (there is no channel to create).
        manager.createNotificationChannel()

        verify(exactly = 0) { mockNotificationManager.createNotificationChannel(any()) }
    }

    // ── PendingIntent is always created ───────────────────────────────────────

    @Test
    fun `scheduleByDelay always creates a PendingIntent with IMMUTABLE flag`() {
        manager.scheduleByDelay("test", 5)

        verify {
            PendingIntent.getBroadcast(
                any(), any(), any(),
                withArg { flags ->
                    assertTrue(
                        "FLAG_IMMUTABLE must be set",
                        flags and PendingIntent.FLAG_IMMUTABLE != 0,
                    )
                },
            )
        }
    }
}
