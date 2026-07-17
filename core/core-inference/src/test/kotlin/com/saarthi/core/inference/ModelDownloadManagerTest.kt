package com.saarthi.core.inference

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.ModelEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ModelDownloadManager is the policy layer deciding whether a model is safe
 * to download, whether a file on disk is genuinely complete, and how a
 * partial download from a prior session gets resumed or discarded — this is
 * where the actual data-integrity and storage-safety decisions live. Real
 * temp directories stand in for context.filesDir/getExternalFilesDir() so
 * these tests exercise real File I/O, not a mocked stand-in for it.
 *
 * Focus areas were chosen from concrete field/review findings this session:
 * the tmp/final same-volume fix, the legacy external-tmp migration bug
 * (a stale partial for an already-completed model was being copied into
 * internal storage where nothing would ever clean it up again), and the
 * isFileComplete() size thresholds that gate whether a file is safe to load.
 */
class ModelDownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var internalDir: File
    private lateinit var externalDir: File
    private lateinit var manager: ModelDownloadManager

    @Before
    fun setUp() {
        internalDir = tempFolder.newFolder("internal")
        externalDir = tempFolder.newFolder("external")

        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns internalDir
        every { mockContext.getExternalFilesDir(null) } returns externalDir

        val mockHfTokenManager = mockk<HuggingFaceTokenManager>(relaxed = true)
        every { mockHfTokenManager.effectiveToken } returns MutableStateFlow("test-token")

        val mockLanguageManager = mockk<LanguageManager>(relaxed = true)
        every { mockLanguageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)

        val mockFailureStore = mockk<DownloadFailureStore>(relaxed = true)

        manager = ModelDownloadManager(mockContext, mockHfTokenManager, mockLanguageManager, mockFailureStore)
    }

    private fun testModel(
        id: String = "test-model",
        fileName: String = "test-model.litertlm",
        fileSizeBytes: Long = 10_000_000L,
    ) = ModelEntry(
        id = id,
        displayName = "Test Model",
        description = "",
        downloadUrl = "https://huggingface.co/org/repo/resolve/abc123/$fileName",
        fileSizeBytes = fileSizeBytes,
        engineType = EngineType.LITERT,
        requiredTier = DeviceTier.LOW,
    )

    private fun writeFile(dir: File, name: String, sizeBytes: Int): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }

    // ── Path helpers — the same-volume fix ──────────────────────────────────

    @Test
    fun `tmpModelsDir and modelsDir share the same root`() {
        // Both must be direct children of filesDir, not one internal and one
        // external — that mismatch was the root cause of the ~2x peak
        // storage bug (renameTo() can't cross mount points, forcing a
        // non-atomic copyTo+delete fallback on every completed download).
        assertEquals(internalDir, manager.modelsDir().parentFile)
        assertEquals(internalDir, manager.tmpModelsDir().parentFile)
    }

    @Test
    fun `localPathFor and tmpPathFor derive from the model file name`() {
        val model = testModel(fileName = "gemma-4-E2B-it.litertlm")
        assertEquals("gemma-4-E2B-it.litertlm", manager.localPathFor(model).name)
        assertEquals("gemma-4-E2B-it.litertlm", manager.tmpPathFor(model).name)
    }

    // ── resolveLocalFile ─────────────────────────────────────────────────────

    @Test
    fun `resolveLocalFile returns the canonical path when it already exists`() {
        val model = testModel()
        val canonical = writeFile(manager.modelsDir(), model.fileName, sizeBytes = 1000)
        assertEquals(canonical.absolutePath, manager.resolveLocalFile(model).absolutePath)
    }

    @Test
    fun `resolveLocalFile renames a DownloadManager-suffixed file to the canonical name`() {
        val model = testModel(fileName = "test-model.litertlm")
        val suffixed = writeFile(manager.modelsDir(), "test-model-1.litertlm", sizeBytes = 1000)

        val resolved = manager.resolveLocalFile(model)

        assertEquals(File(manager.modelsDir(), "test-model.litertlm").absolutePath, resolved.absolutePath)
        assertTrue("Canonical file must exist after rename", resolved.exists())
        assertFalse("Suffixed file must no longer exist", suffixed.exists())
    }

    @Test
    fun `resolveLocalFile returns the canonical non-existent path when nothing is found`() {
        val model = testModel()
        val resolved = manager.resolveLocalFile(model)
        assertFalse(resolved.exists())
        assertEquals(File(manager.modelsDir(), model.fileName).absolutePath, resolved.absolutePath)
    }

    // ── isFileComplete / isDownloaded ────────────────────────────────────────

    @Test
    fun `isFileComplete is false when the file does not exist`() {
        val file = File(tempFolder.root, "nonexistent.litertlm")
        assertFalse(manager.isFileComplete(file, 10_000_000L))
    }

    @Test
    fun `isFileComplete is false for a file under the 1MB minimum`() {
        val file = writeFile(tempFolder.root, "tiny.litertlm", sizeBytes = 500_000)
        assertFalse(manager.isFileComplete(file, 0L))
    }

    @Test
    fun `isFileComplete with no expected size only checks the 1MB floor`() {
        val file = writeFile(tempFolder.root, "sized.litertlm", sizeBytes = 2_000_000)
        assertTrue(manager.isFileComplete(file, 0L))
    }

    @Test
    fun `isFileComplete rejects a file below the 95 percent manual-scan threshold`() {
        val file = writeFile(tempFolder.root, "partial.litertlm", sizeBytes = 9_000_000) // 90% of 10M
        assertFalse(manager.isFileComplete(file, 10_000_000L, trustOS = false))
    }

    @Test
    fun `isFileComplete accepts a file at or above the 95 percent manual-scan threshold`() {
        val file = writeFile(tempFolder.root, "complete.litertlm", sizeBytes = 9_600_000) // 96% of 10M
        assertTrue(manager.isFileComplete(file, 10_000_000L, trustOS = false))
    }

    @Test
    fun `isFileComplete accepts the more generous 85 percent threshold only when trustOS is true`() {
        val file = writeFile(tempFolder.root, "os-reported.litertlm", sizeBytes = 8_800_000) // 88% of 10M
        assertFalse("88% must fail the strict 95% manual-scan threshold", manager.isFileComplete(file, 10_000_000L, trustOS = false))
        assertTrue("88% must pass the generous 85% OS-reported-success threshold", manager.isFileComplete(file, 10_000_000L, trustOS = true))
    }

    @Test
    fun `gguf file with correct magic bytes is complete`() {
        val file = File(tempFolder.root, "model.gguf")
        file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(2_000_000))
        assertTrue(manager.isFileComplete(file, 0L))
    }

    @Test
    fun `gguf file with wrong magic bytes is incomplete despite correct size`() {
        val file = File(tempFolder.root, "corrupt.gguf")
        file.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x00) + ByteArray(2_000_000))
        assertFalse(manager.isFileComplete(file, 0L))
    }

    @Test
    fun `isDownloaded reflects the canonical file completeness`() {
        val model = testModel(fileSizeBytes = 5_000_000L)
        assertFalse(manager.isDownloaded(model))
        writeFile(manager.modelsDir(), model.fileName, sizeBytes = 5_000_000)
        assertTrue(manager.isDownloaded(model))
    }

    // ── Legacy external-tmp migration — the exact review-cycle bug fix ─────────

    @Test
    fun `migration discards legacy tmp directly when the model already completed elsewhere`() {
        // This is the exact bug found in review: migrating unconditionally
        // would copy a multi-GB stale partial into internal storage that
        // nothing would ever clean up again, because reattachActiveDownloads
        // skips an already-complete model and the orphan sweep won't touch a
        // filename that IS a valid catalog entry.
        val model = testModel(fileSizeBytes = 5_000_000L)
        writeFile(manager.modelsDir(), model.fileName, sizeBytes = 5_000_000) // already complete
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, model.fileName, sizeBytes = 3_000_000) // stale legacy partial

        manager.reattachActiveDownloads(listOf(model))

        assertFalse("Legacy partial must be discarded", File(legacyTmpDir, model.fileName).exists())
        assertFalse(
            "Must NOT be copied into the new internal tmp location — that's the exact leak this fixes",
            File(manager.tmpModelsDir(), model.fileName).exists(),
        )
        assertTrue("The completed model itself must be untouched", File(manager.modelsDir(), model.fileName).exists())
    }

    @Test
    fun `migration moves a genuinely incomplete legacy tmp to the internal location`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, model.fileName, sizeBytes = 3_000_000) // well short of complete

        manager.reattachActiveDownloads(listOf(model))

        assertFalse(File(legacyTmpDir, model.fileName).exists())
        val migrated = File(manager.tmpModelsDir(), model.fileName)
        assertTrue(migrated.exists())
        assertEquals(3_000_000L, migrated.length())
    }

    @Test
    fun `migration keeps the legacy copy when it has more bytes than an existing internal partial`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, model.fileName, sizeBytes = 6_000_000) // more complete
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 2_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertEquals(6_000_000L, File(manager.tmpModelsDir(), model.fileName).length())
        assertFalse(File(legacyTmpDir, model.fileName).exists())
    }

    @Test
    fun `migration keeps the internal copy when it has more bytes than the legacy partial`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, model.fileName, sizeBytes = 1_500_000) // less complete
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 4_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertEquals(4_000_000L, File(manager.tmpModelsDir(), model.fileName).length())
        assertFalse("Legacy copy is discarded either way, win or lose", File(legacyTmpDir, model.fileName).exists())
    }

    @Test
    fun `migration deletes legacy tmp files that dont match any current catalog model`() {
        val model = testModel()
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, "deprecated-model.litertlm", sizeBytes = 2_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertFalse(File(legacyTmpDir, "deprecated-model.litertlm").exists())
        assertFalse(File(manager.tmpModelsDir(), "deprecated-model.litertlm").exists())
    }

    @Test
    fun `empty legacy tmp directory is removed after migration`() {
        val model = testModel(fileSizeBytes = 5_000_000L)
        writeFile(manager.modelsDir(), model.fileName, sizeBytes = 5_000_000)
        val legacyTmpDir = File(externalDir, "models_tmp")
        writeFile(legacyTmpDir, model.fileName, sizeBytes = 3_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertFalse("Empty legacy dir should be removed", legacyTmpDir.exists())
    }

    // ── Orphan sweep (internal tmp dir) ─────────────────────────────────────

    @Test
    fun `orphan sweep deletes internal tmp files not matching any catalog model`() {
        val model = testModel()
        writeFile(manager.tmpModelsDir(), "deprecated-model.litertlm", sizeBytes = 2_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertFalse(File(manager.tmpModelsDir(), "deprecated-model.litertlm").exists())
    }

    @Test
    fun `orphan sweep does not delete a tmp file matching a current catalog model`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 3_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertTrue(File(manager.tmpModelsDir(), model.fileName).exists())
    }

    // ── reattachActiveDownloads resume logic ────────────────────────────────

    @Test
    fun `already-complete model is not resumed`() {
        val model = testModel(fileSizeBytes = 5_000_000L)
        writeFile(manager.modelsDir(), model.fileName, sizeBytes = 5_000_000)

        manager.reattachActiveDownloads(listOf(model))

        assertNull(manager.allProgress.value[model.id])
    }

    @Test
    fun `genuine partial tmp file above the 1MB floor triggers resume`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 3_000_000)

        manager.reattachActiveDownloads(listOf(model))

        val progress = manager.allProgress.value[model.id]
        assertTrue("Expected a Downloading state after resume, got $progress", progress is DownloadProgress.Downloading)
    }

    @Test
    fun `tiny partial tmp file below the 1MB floor is not resumed`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 500_000)

        manager.reattachActiveDownloads(listOf(model))

        assertNull(manager.allProgress.value[model.id])
    }

    // ── cancel / restart ─────────────────────────────────────────────────────

    @Test
    fun `cancelDownload removes both the tmp and final files`() {
        val model = testModel(fileSizeBytes = 5_000_000L)
        writeFile(manager.modelsDir(), model.fileName, sizeBytes = 5_000_000)
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 2_000_000)

        manager.cancelDownload(model)

        assertFalse(File(manager.modelsDir(), model.fileName).exists())
        assertFalse(File(manager.tmpModelsDir(), model.fileName).exists())
        assertNull(manager.allProgress.value[model.id])
    }

    @Test
    fun `restartDownload clears the old partial before relaunching from zero`() {
        val model = testModel(fileSizeBytes = 10_000_000L)
        writeFile(manager.tmpModelsDir(), model.fileName, sizeBytes = 3_000_000)

        manager.restartDownload(model)

        val progress = manager.allProgress.value[model.id]
        if (progress is DownloadProgress.Downloading) {
            assertEquals("A restart must not resume from the pre-restart partial", 0L, progress.bytesDownloaded)
        }
    }
}
