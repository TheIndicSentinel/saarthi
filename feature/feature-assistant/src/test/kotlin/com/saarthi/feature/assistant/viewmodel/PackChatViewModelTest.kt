package com.saarthi.feature.assistant.viewmodel

import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.chatInferenceNotReadyMessage
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.DatabaseTransactionRunner
import com.saarthi.feature.assistant.data.KisanPackInstaller
import com.saarthi.feature.assistant.data.RagDocumentRepository
import com.saarthi.feature.assistant.data.RetrievedChunk
import com.saarthi.feature.assistant.data.TtsManager
import com.saarthi.feature.assistant.domain.MessageRole
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * H16 item 4: regression coverage for [PackChatViewModel] — previously
 * untested despite being, per the audit, the single most over-loaded,
 * least-abstracted file in the app after LiteRTInferenceEngine and
 * ChatRepositoryImpl. Covers exactly what the finding named: chat state
 * transitions, MSP-grounding triggers, and state-overlay filtering.
 * Prompt assembly's own text content (buildPackPrompt/buildGeneralFallbackPrompt)
 * is exercised indirectly through these — a dedicated prompt-budget sweep
 * the way ConversationContextAssemblerBudgetTest (H6) does for the main
 * chat is a separate, larger undertaking not attempted here.
 *
 * State-overlay filtering is inline logic inside ask()'s lambda, not a
 * separately extracted function — the only way to observe it is to capture
 * the actual prompt string handed to generateStream() and check which
 * chunk content made it in.
 */
class PackChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var inferenceEngine: InferenceEngine
    private lateinit var ragRepository: RagDocumentRepository
    private lateinit var conversationDao: ConversationDao
    private lateinit var transactionRunner: DatabaseTransactionRunner
    private lateinit var kisanPackPreference: KisanPackPreference
    private lateinit var packInstaller: KisanPackInstaller
    private val userStateFlow = MutableStateFlow("")
    private val isSpeakingFlow = MutableStateFlow(false)

    private fun stubPackSearch(chunks: List<RetrievedChunk> = emptyList()) {
        coEvery {
            ragRepository.search(
                sessionId = any(),
                query = any(),
                topK = any(),
                boostDocUris = any(),
                priorQuery = any(),
                expandSmallFiles = false,
            )
        } returns chunks
    }

    @Before
    fun setUp() {
        mockkObject(InferenceService)
        every { InferenceService.startGenerating(any()) } just Runs
        every { InferenceService.stop(any()) } just Runs

        inferenceEngine = mockk(relaxed = true)
        every { inferenceEngine.isReady } returns true
        every { inferenceEngine.isInitializing } returns false
        every { inferenceEngine.isReloadingAfterRelease } returns false
        every { inferenceEngine.isInitializingFlow } returns MutableStateFlow(false)
        every { inferenceEngine.isReloadingAfterReleaseFlow } returns MutableStateFlow(false)
        every { inferenceEngine.isNativeGenerating } returns false
        every { inferenceEngine.activeModelName } returns "Gemma 3n E4B" // STANDARD+ tier
        every { inferenceEngine.maxContextTokens } returns 2048
        every { inferenceEngine.generateStream(any(), any()) } returns flowOf("An answer.")

        ragRepository = mockk(relaxed = true)
        stubPackSearch()

        conversationDao = mockk(relaxed = true)
        coEvery { conversationDao.getBySession(any()) } returns emptyList()

        transactionRunner = mockk(relaxed = true)
        coEvery { transactionRunner.vacuum() } returns Unit

        kisanPackPreference = mockk(relaxed = true)
        every { kisanPackPreference.userState } returns userStateFlow
        coEvery { kisanPackPreference.setUserState(any()) } answers { userStateFlow.value = firstArg() }

        packInstaller = mockk(relaxed = true)
        coEvery { packInstaller.loadInstalledPack() } returns null
        coEvery { packInstaller.loadMspRecords() } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkObject(InferenceService)
    }

    private fun viewModel(): PackChatViewModel = PackChatViewModel(
        context = mockk(relaxed = true),
        inferenceEngine = inferenceEngine,
        ragRepository = ragRepository,
        conversationDao = conversationDao,
        transactionRunner = transactionRunner,
        languageManager = mockk(relaxed = true) {
            every { selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        },
        ttsManager = mockk<TtsManager>(relaxed = true).also {
            // isSpeaking must actually react to speak()/stop() — toggleSpeak()'s
            // own "stop" branch doesn't clear speakingMessageId itself; a
            // SEPARATE init{} subscription does it when isSpeaking transitions
            // to false (see the ViewModel's own comment on that subscription).
            // A static flow would silently defeat that half of the contract.
            every { it.isSpeaking } returns isSpeakingFlow
            every { it.speak(any(), any()) } answers { isSpeakingFlow.value = true; "mock-utterance-id" }
            every { it.stop() } answers { isSpeakingFlow.value = false }
            every { it.ttsAvailable } returns MutableStateFlow(true)
        },
        kisanPackPreference = kisanPackPreference,
        packInstaller = packInstaller,
        systemPromptProvider = SystemPromptProvider(),
    )

    // ── Chat state transitions ──────────────────────────────────────────

    @Test
    fun `ask with a blank question does nothing`() = runTest {
        val vm = viewModel()

        vm.ask("   ")

        assertTrue(vm.messages.value.isEmpty())
        assertFalse(vm.isGenerating.value)
        verify(exactly = 0) { inferenceEngine.generateStream(any(), any()) }
    }

    @Test
    fun `ask while already generating does nothing`() = runTest {
        // A generateStream that never completes keeps isGenerating stuck
        // true, simulating "a turn is already in flight".
        every { inferenceEngine.generateStream(any(), any()) } returns flow { awaitCancellation() }
        val vm = viewModel()
        vm.ask("first question")
        assertTrue(vm.isGenerating.value)
        val messageCountAfterFirst = vm.messages.value.size

        vm.ask("second question, should be ignored")

        assertEquals("a second ask() while generating must not add any messages", messageCountAfterFirst, vm.messages.value.size)
    }

    @Test
    fun `ask happy path adds the user question and a streaming placeholder, then completes and persists`() = runTest {
        val vm = viewModel()

        vm.ask("What is PM-KISAN?")

        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("What is PM-KISAN?", messages[0].content)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertFalse("generation completed synchronously under UnconfinedTestDispatcher", messages[1].isStreaming)
        assertFalse(vm.isGenerating.value)
        coVerify(exactly = 1) { conversationDao.insert(match { it.role == MessageRole.USER.name && it.content == "What is PM-KISAN?" }) }
        coVerify(exactly = 1) { conversationDao.insert(match { it.role == MessageRole.ASSISTANT.name }) }
    }

    @Test
    fun `retry while generating does nothing`() = runTest {
        every { inferenceEngine.generateStream(any(), any()) } returns flow { awaitCancellation() }
        val vm = viewModel()
        vm.ask("question")
        val messageCount = vm.messages.value.size

        vm.retry(vm.messages.value.last().id)

        assertEquals(messageCount, vm.messages.value.size)
        coVerify(exactly = 0) { conversationDao.deleteById(any()) }
    }

    @Test
    fun `retry removes the assistant reply and its preceding user question, then re-asks the same question`() = runTest {
        val vm = viewModel()
        vm.ask("What is PM-KISAN?")
        val assistantId = vm.messages.value.last().id
        val userId = vm.messages.value.first().id

        vm.retry(assistantId)

        // The old pair is gone from DB, and a fresh pair (same question) exists.
        coVerify(exactly = 1) { conversationDao.deleteById(assistantId) }
        coVerify(exactly = 1) { conversationDao.deleteById(userId) }
        assertEquals(2, vm.messages.value.size)
        assertEquals("What is PM-KISAN?", vm.messages.value[0].content)
        // The re-ask produced a NEW message pair, not a mutation of the old one.
        assertTrue(vm.messages.value[0].id != userId)
    }

    @Test
    fun `clear wipes the message list and deletes the pack session from the DAO`() = runTest {
        val vm = viewModel()
        vm.ask("What is PM-KISAN?")
        assertTrue(vm.messages.value.isNotEmpty())

        vm.clear()

        assertTrue(vm.messages.value.isEmpty())
        coVerify(exactly = 1) { conversationDao.deleteBySession("pack_chat_kisan") }
        coVerify(exactly = 1) { transactionRunner.vacuum() }
    }

    @Test
    fun `toggleSpeak starts speaking, then stops on a second tap of the same message`() = runTest {
        val vm = viewModel()

        vm.toggleSpeak("msg-1", "some answer text")
        assertEquals("msg-1", vm.speakingMessageId.value)

        vm.toggleSpeak("msg-1", "some answer text")
        assertNull(vm.speakingMessageId.value)
    }

    // ── Model-readiness / capability gates ──────────────────────────────

    @Test
    fun `ask when the engine is not ready finishes with packModelNotLoaded, without generating`() = runTest {
        every { inferenceEngine.isReady } returns false
        val vm = viewModel()

        vm.ask("What is PM-KISAN?")

        assertEquals(SupportedLanguage.ENGLISH.packModelNotLoaded, vm.messages.value.last().content)
        verify(exactly = 0) { inferenceEngine.generateStream(any(), any()) }
    }

    @Test
    fun `ask while model reloads shows reload banner not download hint`() = runTest {
        every { inferenceEngine.isReady } returns false
        every { inferenceEngine.isReloadingAfterRelease } returns true
        val vm = viewModel()

        vm.ask("What is PM-KISAN?")

        assertEquals(
            SupportedLanguage.ENGLISH.chatInferenceNotReadyMessage(
                initializing = false,
                reloadingAfterRelease = true,
                modelNotReady = SupportedLanguage.ENGLISH.packModelNotLoaded,
            ),
            vm.messages.value.last().content,
        )
    }

    @Test
    fun `ask on the COMPACT tier finishes with packModelTooSmall, without generating`() = runTest {
        every { inferenceEngine.activeModelName } returns "Gemma 3 1B"
        val vm = viewModel()

        vm.ask("What is PM-KISAN?")

        assertEquals(SupportedLanguage.ENGLISH.packModelTooSmall, vm.messages.value.last().content)
        verify(exactly = 0) { inferenceEngine.generateStream(any(), any()) }
    }

    // ── MSP-grounding triggers ───────────────────────────────────────────

    @Test
    fun `an MSP-trigger question grounds from the official MSP table and never calls BM25 search`() = runTest {
        coEvery { packInstaller.loadMspRecords() } returns listOf(
            KisanPackInstaller.MspRecord(
                crop = "Wheat", cropKey = "wheat", cropHi = "गेहूं", value = 2275, unit = "per quintal",
                season = "Rabi", marketingYear = "2025-26", effectiveDate = "2025-10-01",
                sourceDocument = "CACP Report", sourceUrl = "https://example.gov.in",
            ),
        )
        val promptSlot = slot<String>()
        every { inferenceEngine.generateStream(capture(promptSlot), any()) } returns flowOf("An answer.")
        val vm = viewModel()

        vm.ask("What is the MSP for wheat?")

        coVerify(exactly = 0) {
            ragRepository.search(
                sessionId = any(),
                query = any(),
                topK = any(),
                boostDocUris = any(),
                priorQuery = any(),
                expandSmallFiles = false,
            )
        }
        assertTrue("prompt should include the grounded MSP value", promptSlot.captured.contains("2275"))
    }

    @Test
    fun `a non-MSP question falls through to BM25 search`() = runTest {
        coEvery { packInstaller.loadMspRecords() } returns listOf(
            KisanPackInstaller.MspRecord(
                crop = "Wheat", cropKey = "wheat", cropHi = "गेहूं", value = 2275, unit = "per quintal",
                season = "Rabi", marketingYear = "2025-26", effectiveDate = "2025-10-01",
                sourceDocument = "CACP Report", sourceUrl = "https://example.gov.in",
            ),
        )
        val vm = viewModel()

        vm.ask("How do I apply for PM-KISAN?")

        coVerify(exactly = 1) {
            ragRepository.search(
                sessionId = any(),
                query = any(),
                topK = RagDocumentRepository.DEFAULT_TOP_K,
                boostDocUris = any(),
                priorQuery = any(),
                expandSmallFiles = false,
            )
        }
    }

    @Test
    fun `an MSP-trigger question with no loaded MSP records falls through to BM25 search`() = runTest {
        // setUp's default: loadMspRecords() returns emptyList().
        val vm = viewModel()

        vm.ask("What is the MSP for wheat?")

        coVerify(exactly = 1) {
            ragRepository.search(
                sessionId = any(),
                query = any(),
                topK = any(),
                boostDocUris = any(),
                priorQuery = any(),
                expandSmallFiles = false,
            )
        }
    }

    // ── State-overlay filtering ──────────────────────────────────────────

    private val centralChunk = RetrievedChunk(
        text = "PM-KISAN gives Rs 6000/year in three installments.",
        docName = "PM-KISAN (PMK) — Direct income support", score = 1.0, chunkIndex = 0,
    )
    private val maharashtraChunk = RetrievedChunk(
        text = "Namo Shetkari adds a matching Rs 6000/year for Maharashtra farmers.",
        docName = "Maharashtra — Namo Shetkari (state add-on)", score = 0.9, chunkIndex = 0,
    )
    private val gujaratChunk = RetrievedChunk(
        text = "Kisan Sahay Yojana covers crop loss for Gujarat farmers.",
        docName = "Gujarat — Kisan Sahay Yojana", score = 0.8, chunkIndex = 0,
    )
    private val northEastChunk = RetrievedChunk(
        text = "A special North East bonus applies on top of the central MSP.",
        docName = "North East States — Special MSP Bonus", score = 0.7, chunkIndex = 0,
    )

    private fun promptFor(question: String): String {
        val promptSlot = slot<String>()
        every { inferenceEngine.generateStream(capture(promptSlot), any()) } returns flowOf("An answer.")
        viewModel().ask(question)
        return promptSlot.captured
    }

    @Test
    fun `central chunks are always kept regardless of the user's state`() = runTest {
        stubPackSearch(listOf(centralChunk, maharashtraChunk))
        userStateFlow.value = "Gujarat" // doesn't match Maharashtra's overlay

        val prompt = promptFor("Tell me about farmer schemes")

        assertTrue("central chunk must always be included", prompt.contains("Rs 6000/year in three installments"))
    }

    @Test
    fun `a state-overlay chunk matching the user's own state is kept`() = runTest {
        stubPackSearch(listOf(centralChunk, maharashtraChunk, gujaratChunk))
        userStateFlow.value = "Maharashtra"

        val prompt = promptFor("Tell me about farmer schemes")

        assertTrue(prompt.contains("Namo Shetkari adds a matching"))
    }

    @Test
    fun `a state-overlay chunk for a different state is filtered out`() = runTest {
        stubPackSearch(listOf(centralChunk, maharashtraChunk, gujaratChunk))
        userStateFlow.value = "Maharashtra"

        val prompt = promptFor("Tell me about farmer schemes")

        assertFalse("Gujarat's overlay must not leak into a Maharashtra farmer's prompt", prompt.contains("Kisan Sahay Yojana covers crop loss"))
    }

    @Test
    fun `a North-East overlay chunk is kept when the user's state is a north-eastern state`() = runTest {
        stubPackSearch(listOf(centralChunk, northEastChunk))
        userStateFlow.value = "Manipur"

        val prompt = promptFor("Tell me about farmer schemes")

        assertTrue(prompt.contains("A special North East bonus"))
    }

    @Test
    fun `a North-East overlay chunk is filtered out for a non-north-eastern state`() = runTest {
        stubPackSearch(listOf(centralChunk, northEastChunk))
        userStateFlow.value = "Maharashtra"

        val prompt = promptFor("Tell me about farmer schemes")

        assertFalse(prompt.contains("A special North East bonus"))
    }
}
