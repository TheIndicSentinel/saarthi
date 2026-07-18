package com.saarthi.feature.onboarding.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.DownloadFailureStore
import com.saarthi.core.inference.FunnelTracker
import com.saarthi.core.inference.HuggingFaceTokenManager
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.ModelDownloadManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.EngineType
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * OnboardingViewModel orchestrates the download/resume/init decision logic
 * built up across this session: detecting an in-flight/complete auto-model
 * download after a process kill, skipping the CHAT_TEST confirmation on a
 * resumed flow, re-checking device RAM at the actual download action point
 * rather than trusting a stale screen-load snapshot, and blocking deletion
 * of the currently-active model. None of this had test coverage before —
 * a regression here silently reintroduces exactly the field bugs this
 * session fixed (the Pixel 8 restart-from-scratch report, the delete-while-
 * loaded correctness risk).
 *
 * Every dependency is mocked; each test constructs its own ViewModel after
 * configuring the specific mock state that test needs, since init{}'s
 * resume-detection branches on that state at construction time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockSavedStateHandle: SavedStateHandle
    private lateinit var mockContext: Context
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockInferenceEngine: InferenceEngine
    private lateinit var mockRepository: OnboardingRepository
    private lateinit var mockDeviceProfiler: DeviceProfiler
    private lateinit var mockModelCatalog: ModelCatalog
    private lateinit var mockDownloadManager: ModelDownloadManager
    private lateinit var mockHfTokenManager: HuggingFaceTokenManager
    private lateinit var mockFunnel: FunnelTracker
    private lateinit var mockFailureStore: DownloadFailureStore

    private val defaultProfile = DeviceProfile(
        totalRamMb = 8_000,
        availableRamMb = 4_000,
        safeModelBudgetMb = 3_000,
        availableStorageMb = 10_000,
        cpuCores = 8,
        recommendedThreads = 4,
        hasVulkan = true,
        vulkanVersion = "1.1.0",
        gpuSafe = true,
        abi = "arm64-v8a",
        apiLevel = 34,
        manufacturer = "Google",
    )

    private fun testModel(
        id: String = "auto-model",
        fileName: String = "auto-model.litertlm",
        fileSizeBytes: Long = 10_000_000L,
        displayName: String = "Auto Model",
    ) = ModelEntry(
        id = id,
        displayName = displayName,
        description = "",
        downloadUrl = "https://huggingface.co/org/repo/resolve/abc/$fileName",
        fileSizeBytes = fileSizeBytes,
        engineType = EngineType.LITERT,
        requiredTier = DeviceTier.LOW,
    )

    /**
     * Configures a "clean slate" — nothing to resume, no persisted failure —
     * so init{}'s resume-detection doesn't fire unless a test deliberately
     * overrides isDownloaded/tmpPathFor/lastFailure afterward.
     */
    private fun setUpDefaults(autoModel: ModelEntry?) {
        mockSavedStateHandle = mockk(relaxed = true)
        every { mockSavedStateHandle.get<Boolean>("modelChange") } returns null

        mockContext = mockk(relaxed = true)

        mockLanguageManager = mockk(relaxed = true)
        every { mockLanguageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)

        mockInferenceEngine = mockk(relaxed = true)
        every { mockInferenceEngine.activeModelName } returns null
        coEvery { mockInferenceEngine.initialize(any()) } returns Unit

        mockRepository = mockk(relaxed = true)
        coEvery { mockRepository.scanForModels() } returns emptyList()

        mockDeviceProfiler = mockk(relaxed = true)
        every { mockDeviceProfiler.profile() } returns defaultProfile

        mockModelCatalog = mockk(relaxed = true)
        every { mockModelCatalog.allModels } returns listOfNotNull(autoModel)
        every { mockModelCatalog.recommendedFor(any()) } returns listOfNotNull(autoModel)
        every { mockModelCatalog.autoPick(any()) } returns autoModel

        mockDownloadManager = mockk(relaxed = true)
        every { mockDownloadManager.allProgress } returns MutableStateFlow(emptyMap())
        if (autoModel != null) {
            every { mockDownloadManager.isDownloaded(autoModel) } returns false
            every { mockDownloadManager.tmpPathFor(autoModel) } returns File(tempFolder.root, "nonexistent.tmp")
            every { mockDownloadManager.localPathFor(autoModel) } returns File(tempFolder.root, autoModel.fileName)
        }

        mockHfTokenManager = mockk(relaxed = true)
        every { mockHfTokenManager.token } returns MutableStateFlow("")
        every { mockHfTokenManager.effectiveToken } returns MutableStateFlow("")

        mockFunnel = mockk(relaxed = true)

        mockFailureStore = mockk(relaxed = true)
        every { mockFailureStore.lastFailure } returns flowOf(null)
    }

    /**
     * OnboardingViewModel hardcodes Dispatchers.IO in several places (not an
     * injected/overridable dispatcher), so runTest's virtual scheduler and
     * advanceUntilIdle() cannot observe or wait for that work — it only
     * controls the Main test dispatcher this rule installs. State assertions
     * that depend on IO-dispatched completion poll with a real bounded wait
     * instead of assuming advanceUntilIdle() already accounts for them.
     * MockK's verify(timeout = ...)/coVerify(timeout = ...) is used for call
     * assertions for the same reason.
     */
    private fun awaitCondition(timeoutMs: Long = 2_000, intervalMs: Long = 20, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    @After
    fun tearDown() {
        // init{}'s resume-detection (and deleteModel()/confirmModelAndInit())
        // launch on the real Dispatchers.IO, which runTest/advanceUntilIdle()
        // can't wait for. A straggler from one test can still be executing
        // when the next test's setUpDefaults() replaces this instance's
        // mocks, and a late exception then surfaces as
        // UncaughtExceptionsBeforeTest in whichever LATER test happens to be
        // running — not the one that actually leaked it (same root cause
        // and fix as ManageDownloadsViewModelTest's @After). This drain
        // buffer runs before the next test's fresh mocks are installed,
        // giving straggler work time to finish first.
        Thread.sleep(400)
    }

    private fun createViewModel() = OnboardingViewModel(
        savedStateHandle = mockSavedStateHandle,
        appContext = mockContext,
        languageManager = mockLanguageManager,
        inferenceEngine = mockInferenceEngine,
        repository = mockRepository,
        deviceProfiler = mockDeviceProfiler,
        modelCatalog = mockModelCatalog,
        downloadManager = mockDownloadManager,
        hfTokenManager = mockHfTokenManager,
        funnel = mockFunnel,
        failureStore = mockFailureStore,
    )

    // ── Resume-after-relaunch detection ─────────────────────────────────────

    @Test
    fun `already-complete auto-model triggers resume and skips straight to init`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        every { mockDownloadManager.isDownloaded(model) } returns true
        every { mockDownloadManager.localPathFor(model) } returns File(tempFolder.root, model.fileName)
        // confirmModelAndInitInternal()'s reject-partial-downloads check must
        // see this model as genuinely complete, matching isDownloaded=true.
        every { mockDownloadManager.isFileComplete(any(), any()) } returns true

        createViewModel()
        advanceUntilIdle()

        // The resume-detection block, and confirmModelAndInit() it triggers,
        // both run on Dispatchers.IO — a real background dispatcher runTest's
        // scheduler doesn't control. coVerify's timeout polls for the call
        // instead of assuming advanceUntilIdle() already waited for it.
        coVerify(timeout = 2_000) { mockInferenceEngine.initialize(any()) }
    }

    @Test
    fun `resumed flow skips CHAT_TEST and completes onboarding directly on successful init`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        every { mockDownloadManager.isDownloaded(model) } returns true
        every { mockDownloadManager.localPathFor(model) } returns File(tempFolder.root, model.fileName)
        every { mockDownloadManager.isFileComplete(any(), any()) } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        // isResumedFlow -> onSuccess skips CHAT_TEST and calls completeOnboarding(),
        // which lands on DONE — never CHAT_TEST, unlike the normal first-run path.
        coVerify(timeout = 2_000) { mockRepository.completeOnboarding(any()) }
        awaitCondition { viewModel.uiState.value.step == OnboardingStep.DONE }
    }

    @Test
    fun `genuine partial tmp file triggers resume without re-issuing the download itself`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        every { mockDownloadManager.isDownloaded(model) } returns false
        val partialTmp = File(tempFolder.newFolder("tmp"), model.fileName).also {
            it.writeBytes(ByteArray(3_000_000))
        }
        every { mockDownloadManager.tmpPathFor(model) } returns partialTmp
        every { mockDownloadManager.localPathFor(model) } returns File(tempFolder.root, model.fileName)

        createViewModel()
        advanceUntilIdle()
        Thread.sleep(300) // negative assertion — let the IO coroutine run first

        // The resume-detection path only WAITS for the existing transfer
        // (already resumed by ModelDownloadManager.reattachActiveDownloads) —
        // it must not call startDownload() a second time itself.
        verify(exactly = 0) { mockDownloadManager.startDownload(model) }
    }

    @Test
    fun `nothing to resume and no persisted failure leaves onboarding at the start`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        // isDownloaded=false, tmpPathFor points at a nonexistent file, and
        // lastFailure is null by default -- genuinely nothing to resume from.

        val viewModel = createViewModel()
        advanceUntilIdle()
        // Negative assertions (nothing changed) can't be "awaited" the same
        // way — give the Dispatchers.IO resume-detection coroutine a real
        // bounded window to run before checking it did nothing.
        Thread.sleep(300)

        assertEquals(OnboardingStep.SPLASH, viewModel.uiState.value.step)
        assertNull(viewModel.uiState.value.lastFailureNote)
        coVerify(exactly = 0) { mockInferenceEngine.initialize(any()) }
    }

    @Test
    fun `a persisted failure for the auto-picked model surfaces as lastFailureNote`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        every { mockFailureStore.lastFailure } returns flowOf(model.id to "Not enough storage: needs ~500MB, only 200MB available")

        val viewModel = createViewModel()
        advanceUntilIdle()

        awaitCondition { viewModel.uiState.value.lastFailureNote != null }
        assertEquals(
            "Not enough storage: needs ~500MB, only 200MB available",
            viewModel.uiState.value.lastFailureNote,
        )
    }

    @Test
    fun `a persisted failure for a DIFFERENT model does not surface`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        every { mockFailureStore.lastFailure } returns flowOf("some-other-model" to "unrelated failure")

        val viewModel = createViewModel()
        advanceUntilIdle()
        Thread.sleep(300) // negative assertion — see comment above

        assertNull(viewModel.uiState.value.lastFailureNote)
    }

    // ── Non-resumed (normal, actively-watched) completion keeps CHAT_TEST ──────

    @Test
    fun `normal first-run completion (not resumed) still shows CHAT_TEST, does not skip to DONE`() = runTest {
        val model = testModel()
        setUpDefaults(model) // nothing to resume -> isResumedFlow stays false
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Simulate the user manually proceeding through the picker and
        // downloading, then a successful init -- the non-resumed path.
        every { mockDownloadManager.localPathFor(model) } returns File(tempFolder.root, model.fileName).apply {
            parentFile?.mkdirs(); writeBytes(ByteArray(10_000_000))
        }
        every { mockDownloadManager.isFileComplete(any(), any()) } returns true
        viewModel.highlightModel(model)
        viewModel.confirmModelAndInit()
        advanceUntilIdle()

        // confirmModelAndInit() runs on Dispatchers.IO — await its outcome
        // rather than assuming advanceUntilIdle() already covers it.
        awaitCondition { viewModel.uiState.value.step == OnboardingStep.CHAT_TEST }
        coVerify(exactly = 0) { mockRepository.completeOnboarding(any()) }
    }

    // ── Live RAM recheck at the download action point ───────────────────────

    @Test
    fun `downloadModel refreshes the device profile instead of reusing the init-time snapshot`() = runTest {
        val model = testModel()
        setUpDefaults(model)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.downloadModel(model)

        // Called once during init{}, and again at the actual action point --
        // this is the fix for the review finding that the picker's RAM
        // warning could be stale by the time the user taps Download.
        verify(atLeast = 2) { mockDeviceProfiler.profile() }
    }

    // ── Delete-while-loaded guard (ViewModel-level, not just UI-level) ─────────

    @Test
    fun `deleteModel blocks deleting the currently active model`() = runTest {
        val model = testModel(displayName = "Currently Active Model")
        setUpDefaults(model)
        every { mockInferenceEngine.activeModelName } returns "Currently Active Model"
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteModel(model)
        // The active-model guard returns synchronously, before the
        // Dispatchers.IO launch — no raciness for this specific assertion,
        // unlike the "proceeds normally" case below.
        advanceUntilIdle()

        verify(exactly = 0) { mockDownloadManager.cancelDownload(model) }
        assertTrue(viewModel.uiState.value.error?.contains("currently active") == true)
    }

    @Test
    fun `deleteModel proceeds normally for a model that is not active`() = runTest {
        val model = testModel(displayName = "Some Other Model")
        setUpDefaults(model)
        every { mockInferenceEngine.activeModelName } returns "A Completely Different Model"
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteModel(model)
        advanceUntilIdle()

        // This path DOES launch on Dispatchers.IO — await it explicitly.
        verify(timeout = 2_000) { mockDownloadManager.cancelDownload(model) }
    }
}

class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}
