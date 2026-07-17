package com.saarthi.app

import android.app.Application
import android.os.Environment
import android.os.StatFs
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.ModelDownloadManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.ModelEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * ManageDownloadsViewModel backs the Settings > Manage Downloads screen.
 * deleteModel()'s active-model guard is the ViewModel-level backstop added
 * this session — the screen already disables the delete button for the
 * active model, but that's UI-level only; this is what actually stops the
 * deletion from happening if that UI-level control is ever stale or
 * bypassed. Zero prior coverage existed for this class.
 *
 * Both refresh() and deleteModel() launch on Dispatchers.IO (a real
 * background dispatcher, not the test dispatcher this rule installs on
 * Main) — MockK's verify(timeout = ...) is used wherever an assertion
 * depends on that work actually completing, rather than assuming
 * advanceUntilIdle() already covers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageDownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockApplication: Application
    private lateinit var mockModelCatalog: ModelCatalog
    private lateinit var mockDownloadManager: ModelDownloadManager
    private lateinit var mockInferenceEngine: InferenceEngine

    private fun testModel(
        id: String = "test-model",
        displayName: String = "Test Model",
    ) = ModelEntry(
        id = id,
        displayName = displayName,
        description = "",
        downloadUrl = "https://huggingface.co/org/repo/resolve/abc/test-model.litertlm",
        fileSizeBytes = 10_000_000L,
        engineType = EngineType.LITERT,
        requiredTier = DeviceTier.LOW,
    )

    @Before
    fun setUp() {
        mockApplication = mockk(relaxed = true)
        mockModelCatalog = mockk(relaxed = true)
        every { mockModelCatalog.allModels } returns emptyList()
        mockDownloadManager = mockk(relaxed = true)
        mockInferenceEngine = mockk(relaxed = true)
        every { mockInferenceEngine.activeModelName } returns null

        // refresh() (called from init{}) constructs a real StatFs against
        // Environment.getDataDirectory() — neither is injected, so both are
        // intercepted the same way DeviceProfilerTest already established.
        mockkStatic(Environment::class)
        every { Environment.getDataDirectory() } returns tempFolder.root
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().totalBytes } returns 100_000_000_000L
        every { anyConstructed<StatFs>().availableBytes } returns 50_000_000_000L
    }

    @After
    fun tearDown() {
        unmockkStatic(Environment::class)
        unmockkConstructor(StatFs::class)
    }

    private fun createViewModel() = ManageDownloadsViewModel(
        mockApplication, mockModelCatalog, mockDownloadManager, mockInferenceEngine,
    )

    // ── Delete-while-loaded guard ────────────────────────────────────────────

    @Test
    fun `deleteModel blocks deleting the currently active model`() = runTest {
        val model = testModel(displayName = "Currently Active Model")
        every { mockInferenceEngine.activeModelName } returns "Currently Active Model"
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteModel(model)
        // The guard returns synchronously before the Dispatchers.IO launch —
        // no raciness for this specific assertion.
        advanceUntilIdle()

        verify(exactly = 0) { mockDownloadManager.cancelDownload(model) }
        assertTrue(viewModel.uiState.value.error?.contains("currently active") == true)
    }

    @Test
    fun `deleteModel proceeds normally for a model that is not active`() = runTest {
        val model = testModel(displayName = "Some Other Model")
        every { mockInferenceEngine.activeModelName } returns "A Completely Different Model"
        every { mockDownloadManager.localPathFor(model) } returns File(tempFolder.root, "test-model.litertlm")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteModel(model)
        advanceUntilIdle()

        verify(timeout = 2_000) { mockDownloadManager.cancelDownload(model) }
    }

    @Test
    fun `deleteModel error is cleared by a subsequent successful refresh`() = runTest {
        val model = testModel(displayName = "Currently Active Model")
        every { mockInferenceEngine.activeModelName } returns "Currently Active Model"
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteModel(model) // blocked, sets error
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.error != null)

        viewModel.refresh()
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.uiState.value.error != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(null, viewModel.uiState.value.error)
    }

    // ── refresh() ────────────────────────────────────────────────────────────

    @Test
    fun `refresh reports the active model name from the engine`() = runTest {
        every { mockInferenceEngine.activeModelName } returns "Whatever Is Loaded"
        val viewModel = createViewModel()

        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.uiState.value.activeModelName == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("Whatever Is Loaded", viewModel.uiState.value.activeModelName)
    }

    @Test
    fun `refresh marks the installed model matching the active engine model as active`() = runTest {
        val activeModel = testModel(id = "active", displayName = "Active Model")
        val otherModel = testModel(id = "other", displayName = "Other Model")
        every { mockModelCatalog.allModels } returns listOf(activeModel, otherModel)
        every { mockDownloadManager.isDownloaded(any()) } returns true
        every { mockDownloadManager.localPathFor(any()) } answers {
            File(tempFolder.root, (it.invocation.args[0] as ModelEntry).id)
        }
        every { mockInferenceEngine.activeModelName } returns "Active Model"

        val viewModel = createViewModel()
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.uiState.value.installed.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        val installed = viewModel.uiState.value.installed
        assertEquals(2, installed.size)
        assertTrue(installed.first { it.entry.id == "active" }.active)
        assertTrue(!installed.first { it.entry.id == "other" }.active)
    }
}

class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}
