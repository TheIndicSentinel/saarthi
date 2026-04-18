package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.ChatRepository
import com.saarthi.feature.assistant.domain.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val memoryRepository: MemoryRepository,
    private val fileContentExtractor: FileContentExtractor,
    private val languageManager: LanguageManager,
) : ChatRepository {

    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _tokensPerSecond = MutableStateFlow(0f)

    override fun getHistory(): Flow<List<ChatMessage>> = _history.asStateFlow()
    override fun getTokensPerSecond(): Flow<Float> = _tokensPerSecond.asStateFlow()

    override fun streamResponse(userMessage: String, attachments: List<AttachedFile>): Flow<String> {
        val userMsg = ChatMessage(
            content = userMessage,
            role = MessageRole.USER,
            attachments = attachments,
        )
        _history.update { it + userMsg }

        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = streamingId,
            content = "",
            role = MessageRole.ASSISTANT,
            isStreaming = true,
        )
        _history.update { it + placeholder }

        val accumulated = StringBuilder()
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        val prompt = buildPrompt(userMessage, attachments)

        return inferenceEngine.generateStream(prompt, PackType.BASE)
            .onEach { token ->
                accumulated.append(token)
                tokenCount++
                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                if (elapsed > 0) _tokensPerSecond.value = tokenCount / elapsed
                _history.update { history ->
                    history.map { msg ->
                        if (msg.id == streamingId)
                            msg.copy(content = accumulated.toString(), tokenCount = tokenCount)
                        else msg
                    }
                }
            }
            .onCompletion {
                _tokensPerSecond.value = 0f
                _history.update { history ->
                    history.map { msg ->
                        if (msg.id == streamingId) msg.copy(isStreaming = false)
                        else msg
                    }
                }
            }
    }

    override suspend fun clearHistory() = _history.update { emptyList() }

    override suspend fun deleteMessage(id: String) =
        _history.update { it.filterNot { msg -> msg.id == id } }

    private fun buildPrompt(userMessage: String, attachments: List<AttachedFile>): String = buildString {
        appendLine("You are Saarthi, a helpful and knowledgeable AI assistant.")
        appendLine("Be concise, accurate, and friendly.")
        appendLine()
        if (attachments.isNotEmpty()) {
            append(fileContentExtractor.buildPromptContext(attachments))
        }
        appendLine("User: $userMessage")
        append("Assistant:")
    }
}
