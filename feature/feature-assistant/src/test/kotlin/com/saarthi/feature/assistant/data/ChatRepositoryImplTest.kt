package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.memory.db.ChatSessionDao
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryImplTest {

    private val inferenceEngine = mockk<InferenceEngine>(relaxed = true)
    private val memoryRepository = mockk<MemoryRepository>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val chatSessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val languageManager = mockk<LanguageManager>(relaxed = true)
    private val reminderManager = mockk<ReminderManager>(relaxed = true)
    private val fileExtractor = mockk<FileContentExtractor>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private lateinit var repository: ChatRepositoryImpl

    @Before
    fun setup() {
        every { languageManager.selectedLanguage } returns MutableStateFlow(SupportedLanguage.ENGLISH)
        coEvery { chatSessionDao.getAll() } returns emptyList()
        repository = ChatRepositoryImpl(
            context,
            inferenceEngine,
            memoryRepository,
            conversationDao,
            chatSessionDao,
            fileExtractor,
            languageManager,
            reminderManager
        )
    }

    @Test
    fun `buildPrompt preserves system directive`() = runTest {
        // Accessing private method via reflection for testing complex prompt building logic
        val buildPromptMethod = ChatRepositoryImpl::class.java.getDeclaredMethod("buildPrompt", String::class.java, List::class.java)
        buildPromptMethod.isAccessible = true
        
        val prompt = buildPromptMethod.invoke(repository, "Hello", emptyList<Any>()) as String
        
        assertTrue("Prompt should contain SYSTEM_DIRECTIVE", prompt.contains("[SYSTEM_DIRECTIVE]"))
        assertTrue("Prompt should contain user message", prompt.contains("Hello"))
    }

    @Test
    fun `trimPrompt keeps system turn and recent turns`() = runTest {
        val trimPromptMethod = ChatRepositoryImpl::class.java.getDeclaredMethod("trimPrompt", String::class.java)
        trimPromptMethod.isAccessible = true
        
        val longPrompt = buildString {
            append("<start_of_turn>user\n[SYSTEM_DIRECTIVE]\nRule 1\n[/SYSTEM_DIRECTIVE]\n<end_of_turn>\n")
            repeat(20) { i ->
                append("<start_of_turn>user\nTurn $i<end_of_turn>\n")
                append("<start_of_turn>model\nReply $i<end_of_turn>\n")
            }
            append("<start_of_turn>user\nLatest message<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        
        val trimmed = trimPromptMethod.invoke(repository, longPrompt) as String
        
        assertTrue("Trimmed prompt should still have SYSTEM_DIRECTIVE", trimmed.contains("[SYSTEM_DIRECTIVE]"))
        assertTrue("Trimmed prompt should have Latest message", trimmed.contains("Latest message"))
        assertFalse("Trimmed prompt should have discarded early turns", trimmed.contains("Turn 0"))
    }
}
