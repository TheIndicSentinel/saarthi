package com.saarthi.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 3.1: [DebugLogger] is append-only and, before rotation, grew without
 * bound over the app's lifetime — an unbounded on-device footprint of
 * potentially sensitive diagnostic text. These tests exercise the size guard
 * ([DebugLogger.rotateFileIfNeeded]) that bounds that footprint to at most two
 * generations (active + a single "<name>.1" backup).
 *
 * The guard is driven against a [TemporaryFolder] file rather than Android's
 * files dir so it runs as a plain JVM unit test with no Robolectric/instrumented
 * dependency. The MediaStore variant (public-Downloads sink, beta only) shares
 * this exact size/rollover rule and is the thin resolver adapter of it; it needs
 * a live ContentResolver and so is validated by the app rather than here.
 */
class DebugLoggerRotationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun File.backup() = File(parentFile, "$name.1")

    @Test
    fun `below the cap no rollover happens and content is preserved`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        val content = "line under the cap\n"
        log.writeText(content)

        DebugLogger.rotateFileIfNeeded(log, maxBytes = 1024)

        assertTrue("primary must remain", log.exists())
        assertEquals("content must be untouched", content, log.readText())
        assertFalse("no backup should be created below the cap", log.backup().exists())
    }

    @Test
    fun `at exactly the cap no rollover happens`() {
        // Guard fires only when length strictly exceeds the cap.
        val log = tempFolder.newFile("saarthi_debug.log")
        log.writeBytes(ByteArray(64))

        DebugLogger.rotateFileIfNeeded(log, maxBytes = 64)

        assertTrue(log.exists())
        assertEquals(64, log.length())
        assertFalse(log.backup().exists())
    }

    @Test
    fun `exceeding the cap rolls the primary over to the backup`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        val original = "A".repeat(200)
        log.writeText(original)

        DebugLogger.rotateFileIfNeeded(log, maxBytes = 100)

        // Primary is left absent so the next append starts a fresh generation;
        // the old content survives in the single backup.
        assertFalse("primary should be rolled away", log.exists())
        assertTrue("backup should now exist", log.backup().exists())
        assertEquals("backup must hold the prior content", original, log.backup().readText())
    }

    @Test
    fun `a second rollover replaces the previous backup without unbounded accumulation`() {
        val log = tempFolder.newFile("saarthi_debug.log")

        // First generation overflows and rolls over.
        val firstGen = "1".repeat(200)
        log.writeText(firstGen)
        DebugLogger.rotateFileIfNeeded(log, maxBytes = 100)
        assertEquals(firstGen, log.backup().readText())

        // A fresh primary fills up again and rolls over a second time.
        val secondGen = "2".repeat(200)
        log.writeText(secondGen)
        DebugLogger.rotateFileIfNeeded(log, maxBytes = 100)

        // Only the newest backup survives — the first generation is gone.
        assertEquals("backup must be replaced, not appended", secondGen, log.backup().readText())

        // At most two generations exist on disk: nothing beyond "<name>" and
        // "<name>.1" is ever created (no ".2", ".3", ...).
        val siblings = log.parentFile!!.listFiles { f -> f.name.startsWith("saarthi_debug.log") }!!
        assertTrue(
            "no generation beyond the single backup should exist, found: ${siblings.map { it.name }}",
            siblings.none { it.name.endsWith(".2") || it.name.endsWith(".3") },
        )
    }

    @Test
    fun `total retained data stays bounded by two generations`() {
        val log = tempFolder.newFile("saarthi_debug.log")
        val cap = 100L

        // Simulate many append+rotate cycles far exceeding the cap.
        repeat(50) { i ->
            log.appendText("x".repeat(200))
            DebugLogger.rotateFileIfNeeded(log, maxBytes = cap)
            log.appendText("cycle-$i\n")
        }

        val primaryLen = if (log.exists()) log.length() else 0L
        val backupLen = if (log.backup().exists()) log.backup().length() else 0L
        // Each generation is bounded by cap + a single over-cap append, so the
        // retained total can never approach the unbounded pre-rotation growth.
        assertTrue(
            "retained bytes ($primaryLen + $backupLen) must stay small and bounded",
            primaryLen + backupLen <= 2 * (cap + 300),
        )
    }
}
