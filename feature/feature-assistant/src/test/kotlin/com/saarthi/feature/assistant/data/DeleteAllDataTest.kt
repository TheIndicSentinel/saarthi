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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regression guard for the "Delete all conversations" Settings action.
 *
 * The prior bug: Settings routed to [ChatRepositoryImpl.clearHistory], which
 * only clears the CURRENT session and never touches the durable USER_SCOPE
 * profile memory — despite the UI promising "permanently delete all
 * conversations". [ChatRepositoryImpl.deleteAllData] must cascade EVERY chat
 * session AND wipe USER_SCOPE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAllDataTest {

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

    @Test
    fun `deleteAllData cascades every session and wipes USER_SCOPE`() = runTest {
        every { languageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        coEvery { chatSessionDao.getAll() } returns listOf(session("s1"), session("s2"))
        // The runner must actually execute the cascade so the deletes below run.
        coEvery { transactionRunner.runInTransaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }

        val repo = createRepo()

        repo.deleteAllData()

        // Every session's messages + RAG chunks cascade-deleted.
        coVerify { conversationDao.deleteBySession("s1") }
        coVerify { conversationDao.deleteBySession("s2") }
        coVerify { ragRepository.deleteForSession("s1") }
        coVerify { ragRepository.deleteForSession("s2") }
        coVerify { memoryRepository.deleteForSession("s1") }
        coVerify { memoryRepository.deleteForSession("s2") }

        // The regression guard: the durable cross-chat profile memory is wiped.
        coVerify { memoryRepository.deleteForSession(MemoryRepository.USER_SCOPE) }

        // Session rows themselves removed.
        coVerify { chatSessionDao.deleteById("s1") }
        coVerify { chatSessionDao.deleteById("s2") }
    }

    @Test
    fun `deleteAllData does not use single-session clear semantics`() = runTest {
        every { languageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        coEvery { chatSessionDao.getAll() } returns listOf(session("s1"), session("s2"))
        coEvery { transactionRunner.runInTransaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }

        val repo = createRepo()
        repo.deleteAllData()

        // clearHistory resets a session's title to "New Chat" instead of deleting
        // it; deleteAllData must actually remove the rows, not rename them.
        coVerify(exactly = 0) { chatSessionDao.updateTitleAndTimestamp("s1", "New Chat", any()) }
        coVerify(exactly = 0) { chatSessionDao.updateTitleAndTimestamp("s2", "New Chat", any()) }
    }
}
