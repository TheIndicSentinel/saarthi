package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.PersonalityPreference
import com.saarthi.core.i18n.ResponseStyleManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.core.memory.db.ChatSessionDao
import com.saarthi.core.memory.db.ChatSessionEntity
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.DatabaseTransactionRunner
import com.saarthi.core.memory.domain.MemoryRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drawer re-taps used to call [InferenceEngine.resetSession] on every
 * [ChatRepositoryImpl.switchSession], which JNI-created a replacement
 * Conversation even when the already-open chat was selected again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchSessionResetTest {

    private val context: Context = mockk(relaxed = true)
    private val conversationDao: ConversationDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val transactionRunner: DatabaseTransactionRunner = mockk(relaxed = true)
    private val memoryRepository: MemoryRepository = mockk(relaxed = true)
    private val languageManager: LanguageManager = mockk(relaxed = true)
    private val inferenceEngine: InferenceEngine = mockk(relaxed = true)
    private val deviceProfiler: DeviceProfiler = mockk(relaxed = true)
    private val systemPromptProvider: SystemPromptProvider = mockk(relaxed = true)
    private val responseStyleManager: ResponseStyleManager = mockk(relaxed = true)
    private val personalityPreference: PersonalityPreference = mockk(relaxed = true)
    private val ragRepository: RagDocumentRepository = mockk(relaxed = true)
    private val implicitFactExtractor: ImplicitFactExtractor = mockk(relaxed = true)
    private val responseStyleInstructionCompiler: ResponseStyleInstructionCompiler = mockk(relaxed = true)

    private fun session(id: String) = ChatSessionEntity(
        id = id,
        title = "New Chat",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun createRepo() = ChatRepositoryImpl(
        context = context,
        conversationDao = conversationDao,
        chatSessionDao = chatSessionDao,
        transactionRunner = transactionRunner,
        memoryRepository = memoryRepository,
        languageManager = languageManager,
        inferenceEngine = inferenceEngine,
        deviceProfiler = deviceProfiler,
        systemPromptProvider = systemPromptProvider,
        responseStyleManager = responseStyleManager,
        personalityPreference = personalityPreference,
        ragRepository = ragRepository,
        implicitFactExtractor = implicitFactExtractor,
        responseStyleInstructionCompiler = responseStyleInstructionCompiler,
    )

    /**
     * Wait until init has assigned [ChatRepositoryImpl]'s current session from
     * Room. That assignment runs on a real IO dispatcher and would otherwise
     * race the assertions (overwriting currentSessionId between two taps).
     */
    private fun stubAndAwaitRestoredSession(sessionId: String): ChatRepositoryImpl {
        every { languageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        coEvery { chatSessionDao.getAll() } returns listOf(session(sessionId))
        coEvery { conversationDao.getRecentBySession(any(), any()) } returns emptyList()
        val restored = CountDownLatch(1)
        coEvery { conversationDao.getRecentBySession(sessionId, any()) } answers {
            restored.countDown()
            emptyList()
        }
        coEvery { ragRepository.hasIndexedDocs(any()) } returns false
        val repo = createRepo()
        assertTrue(
            "init did not restore session $sessionId",
            restored.await(2, TimeUnit.SECONDS),
        )
        clearMocks(inferenceEngine, answers = false, recordedCalls = true)
        return repo
    }

    @Test
    fun `re-selecting the current session does not call resetSession`() = runTest {
        val repo = stubAndAwaitRestoredSession("chat-a")
        repo.switchSession("chat-a")
        coVerify(exactly = 0) { inferenceEngine.resetSession() }
    }

    @Test
    fun `switching to a different session calls resetSession`() = runTest {
        val repo = stubAndAwaitRestoredSession("chat-a")
        repo.switchSession("chat-b")
        coVerify(exactly = 1) { inferenceEngine.resetSession() }
    }

    @Test
    fun `re-selecting after a real switch does not call resetSession again`() = runTest {
        val repo = stubAndAwaitRestoredSession("chat-a")
        repo.switchSession("chat-b")
        repo.switchSession("chat-b")
        coVerify(exactly = 1) { inferenceEngine.resetSession() }
    }
}
