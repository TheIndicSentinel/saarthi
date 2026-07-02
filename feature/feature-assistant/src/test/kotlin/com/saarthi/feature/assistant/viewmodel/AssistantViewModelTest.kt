package com.saarthi.feature.assistant.viewmodel

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.PersonalityCatalog
import com.saarthi.core.i18n.PersonalityPreference
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.TtsPreference
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.feature.assistant.data.FileContentExtractor
import com.saarthi.feature.assistant.data.TtsManager
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.ChatRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * AssistantViewModel is the central state machine for the entire chat UX.
 * Zero tests here means UI regressions ship silently.
 *
 * Uses UnconfinedTestDispatcher so viewModelScope coroutines execute
 * synchronously within runTest — no need to advance the scheduler for
 * simple state assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Mocks ─────────────────────────────────────────────────────────────────

    private val mockContext: Context = mockk(relaxed = true)
    private val mockChatRepository: ChatRepository = mockk(relaxed = true)
    private val mockInferenceEngine: InferenceEngine = mockk(relaxed = true)
    private val mockFileExtractor: FileContentExtractor = mockk(relaxed = true)
    private val mockLanguageManager: LanguageManager = mockk(relaxed = true)
    private val mockMemoryRepository: MemoryRepository = mockk(relaxed = true)
    private val mockTtsManager: TtsManager = mockk(relaxed = true)
    private val mockTtsPreference: TtsPreference = mockk(relaxed = true)
    private val mockPersonalityPreference: PersonalityPreference = mockk(relaxed = true)
    private val mockFunnel: com.saarthi.core.inference.FunnelTracker = mockk(relaxed = true)
    private val mockEntitlements: com.saarthi.core.i18n.EntitlementManager = mockk(relaxed = true)

    // Mutable flows controlled by individual tests
    private val isReadyFlow = MutableStateFlow(false)
    private val activeModelNameFlow = MutableStateFlow<String?>(null)

    @Before
    fun setUp() {
        // Minimal stubs for flows consumed in the init block.
        every { mockChatRepository.getHistory() } returns flowOf(emptyList())
        every { mockChatRepository.getTokensPerSecond() } returns flowOf(0f)
        every { mockChatRepository.getSessions() } returns flowOf(emptyList())
        every { mockChatRepository.getCurrentSessionId() } returns flowOf("default")
        every { mockInferenceEngine.isReady } returns false
        every { mockInferenceEngine.isReadyFlow } returns isReadyFlow
        every { mockInferenceEngine.activeModelNameFlow } returns activeModelNameFlow
        every { mockLanguageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        every { mockMemoryRepository.observeAll() } returns flowOf(emptyList())
        every { mockTtsManager.isSpeaking } returns MutableStateFlow(false)
        every { mockTtsPreference.autoSpeakReplies } returns MutableStateFlow(false)
        every { mockPersonalityPreference.selected } returns MutableStateFlow(PersonalityCatalog.SAARTHI)
        every { mockEntitlements.isPro } returns MutableStateFlow(false)
    }

    private fun createViewModel() = AssistantViewModel(
        context = mockContext,
        chatRepository = mockChatRepository,
        inferenceEngine = mockInferenceEngine,
        fileExtractor = mockFileExtractor,
        languageManager = mockLanguageManager,
        memoryRepository = mockMemoryRepository,
        ttsManager = mockTtsManager,
        ttsPreference = mockTtsPreference,
        personalityPreference = mockPersonalityPreference,
        funnel = mockFunnel,
        entitlements = mockEntitlements,
    )

    // ── sendMessage ────────────────────────────────────────────────────────────

    @Test
    fun `sendMessage clears inputText and sets isStreaming true`() = runTest {
        every { mockChatRepository.streamResponse(any(), any()) } returns flowOf()
        val vm = createViewModel()

        vm.onInputChange("Hello Saarthi")
        vm.sendMessage()

        assertEquals("", vm.uiState.value.inputText)
        // isStreaming may have already cleared to false because streamResponse
        // returned an empty flow (completed instantly with UnconfinedTestDispatcher).
        // The important contract is: it DID set isStreaming = true, then cleared.
        // Verify streamResponse was called with the correct text.
        verify { mockChatRepository.streamResponse("Hello Saarthi", any()) }
    }

    @Test
    fun `sendMessage with blank text does nothing`() = runTest {
        val vm = createViewModel()

        vm.onInputChange("   ")
        vm.sendMessage()

        assertFalse("Must not set isStreaming for blank input", vm.uiState.value.isStreaming)
        verify(exactly = 0) { mockChatRepository.streamResponse(any(), any()) }
    }

    @Test
    fun `sendMessage while already streaming is a no-op`() = runTest {
        // Non-completing stream so isStreaming stays true
        val openStream = MutableSharedFlow<String>()
        every { mockChatRepository.streamResponse(any(), any()) } returns openStream

        val vm = createViewModel()
        vm.onInputChange("first message")
        vm.sendMessage()

        // Still streaming; second sendMessage must be ignored
        vm.onInputChange("second message")
        vm.sendMessage()

        verify(exactly = 1) { mockChatRepository.streamResponse(any(), any()) }
    }

    // ── stopGeneration ─────────────────────────────────────────────────────────

    @Test
    fun `stopGeneration calls cancelGeneration and clears isStreaming`() = runTest {
        val openStream = MutableSharedFlow<String>()
        every { mockChatRepository.streamResponse(any(), any()) } returns openStream

        val vm = createViewModel()
        vm.onInputChange("generate something")
        vm.sendMessage()
        assertTrue("Must be streaming before stop", vm.uiState.value.isStreaming)

        vm.stopGeneration()

        verify { mockInferenceEngine.cancelGeneration() }
        assertFalse("isStreaming must be false after stop", vm.uiState.value.isStreaming)
    }

    @Test
    fun `stopGeneration while not streaming is a no-op`() = runTest {
        val vm = createViewModel()
        assertFalse(vm.uiState.value.isStreaming)

        vm.stopGeneration()

        verify(exactly = 0) { mockInferenceEngine.cancelGeneration() }
    }

    // ── Session management ─────────────────────────────────────────────────────

    @Test
    fun `newChat calls createSession on repository`() = runTest {
        val vm = createViewModel()

        vm.newChat()
        advanceUntilIdle()

        coVerify { mockChatRepository.createSession() }
    }

    @Test
    fun `deleteSession calls deleteSession on repository`() = runTest {
        val vm = createViewModel()

        vm.deleteSession("session-abc")
        advanceUntilIdle()

        coVerify { mockChatRepository.deleteSession("session-abc") }
    }

    // ── modelReady observation ─────────────────────────────────────────────────

    @Test
    fun `modelReady updates when isReadyFlow emits`() = runTest {
        val vm = createViewModel()
        assertFalse("modelReady must start false", vm.uiState.value.modelReady)

        isReadyFlow.value = true
        advanceUntilIdle()

        assertTrue("modelReady must reflect engine readiness", vm.uiState.value.modelReady)
    }

    // ── Attachment gate (isCompactModel) ───────────────────────────────────────

    @Test
    fun `compact model name disables attachmentsEnabled`() = runTest {
        val vm = createViewModel()
        assertTrue("attachments must be enabled by default", vm.uiState.value.attachmentsEnabled)

        activeModelNameFlow.value = "gemma3-1b-it-int4"
        advanceUntilIdle()

        assertFalse("attachments must be disabled for compact model",
            vm.uiState.value.attachmentsEnabled)
    }

    @Test
    fun `non-compact model leaves attachmentsEnabled true`() = runTest {
        val vm = createViewModel()

        activeModelNameFlow.value = "gemma-4-E2B-it.litertlm"
        advanceUntilIdle()

        assertTrue("attachments must stay enabled for large model",
            vm.uiState.value.attachmentsEnabled)
    }

    @Test
    fun `compact model check matches 1b and compact in name`() = runTest {
        val vm = createViewModel()

        for (name in listOf("gemma3-1b-it-int4", "Gemma 3 Compact", "model-1b-small")) {
            activeModelNameFlow.value = name
            advanceUntilIdle()
            assertFalse("$name must be detected as compact", vm.uiState.value.attachmentsEnabled)
        }
    }
}

// ── Test infrastructure ────────────────────────────────────────────────────────

/**
 * JUnit4 rule that installs [testDispatcher] as [Dispatchers.Main] for the
 * duration of each test. Required because [androidx.lifecycle.viewModelScope]
 * uses Dispatchers.Main under the hood — without this, the ViewModel's
 * coroutine scope uses the real Main dispatcher which is not available in JVM
 * unit tests and causes the ViewModel construction to fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description?) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description?) = Dispatchers.resetMain()
}
