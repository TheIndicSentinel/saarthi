package com.saarthi.core.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Settings "delete all conversations" must wipe `saarthi_debug.log` so a
 * later Support attachment cannot replay prior session text. These tests
 * drive [DebugLogger.wipe] against a [TemporaryFolder] file sink (same
 * hook as [DebugLoggerFlushTest]) — no Robolectric/MediaStore.
 */
class DebugLoggerWipeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun File.backup() = File(parentFile, "$name.1")

    @Test
    fun `wipe removes prior lines and the backup generation`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        DebugLogger.bindFileSinkForTests(log)
        val secret = "secret-payload-${System.nanoTime()}"
        DebugLogger.log("TEST", secret)
        DebugLogger.flushBlocking(timeoutMs = 1_000)
        assertTrue(log.readText().contains(secret))

        val backup = log.backup()
        backup.writeText("old-backup-generation")

        DebugLogger.wipe()
        DebugLogger.flushBlocking(timeoutMs = 1_000)

        assertFalse("backup generation must be deleted", backup.exists())
        val after = if (log.exists()) log.readText() else ""
        assertFalse("wiped log must not retain prior payload", after.contains(secret))
        assertTrue(
            "wipe must leave a session marker for later logs. Got:\n$after",
            after.contains("wiped (delete-all)"),
        )
    }

    @Test
    fun `wipe is a no-throw when the primary file is already gone`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        DebugLogger.bindFileSinkForTests(log)
        log.delete()
        DebugLogger.wipe()
        DebugLogger.flushBlocking(timeoutMs = 1_000)
        val after = if (log.exists()) log.readText() else ""
        assertTrue(after.contains("wiped (delete-all)"))
    }
}
