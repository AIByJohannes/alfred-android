package com.aibyjohannes.alfred.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class RenderMode {
    PLAIN,
    MARKDOWN
}

data class UiChatMessage(
    val id: Long = 0L,
    val content: String,
    val isUser: Boolean,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val renderMode: RenderMode = RenderMode.MARKDOWN,
    val showTypingDots: Boolean = false
)

class HomeViewModel : ViewModel() {
    companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 60L
        private const val STREAM_FLUSH_CHAR_THRESHOLD = 24
    }

    private var repository: ChatRepository? = null
    private var apiKeyStore: ApiKeyStore? = null

    private val _messages = MutableLiveData<List<UiChatMessage>>(emptyList())
    val messages: LiveData<List<UiChatMessage>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _needsApiKey = MutableLiveData(false)
    val needsApiKey: LiveData<Boolean> = _needsApiKey

    // Keep track of conversation history for context
    private val conversationHistory = mutableListOf<ChatMessage>()
    private var nextMessageId = 1L

    fun initialize(apiKeyStore: ApiKeyStore, repository: ChatRepository, greetingMessage: String) {
        this.apiKeyStore = apiKeyStore
        this.repository = repository
        checkApiKey()

        // Initialize conversation with greeting message
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(
                ChatMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = greetingMessage
                )
            )
        }
    }

    fun checkApiKey() {
        _needsApiKey.value = apiKeyStore?.hasApiKey() != true
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return

        val repo = repository ?: return

        val userMessageId = nextId()
        val assistantMessageId = nextId()

        appendUiMessage(
            UiChatMessage(
                id = userMessageId,
                content = userInput,
                isUser = true
            )
        )

        appendUiMessage(
            UiChatMessage(
                id = assistantMessageId,
                content = "",
                isUser = false,
                isStreaming = true,
                renderMode = RenderMode.PLAIN,
                showTypingDots = false
            )
        )

        _isLoading.value = true

        viewModelScope.launch {
            val userHistoryMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = userInput)
            val visibleAssistantContent = StringBuilder()
            val pendingAssistantContent = StringBuilder()
            var lastFlushAtMs = 0L

            try {
                repo.streamMessage(userInput, conversationHistory).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            pendingAssistantContent.append(event.textChunk)
                            val now = System.currentTimeMillis()
                            val shouldFlush = pendingAssistantContent.length >= STREAM_FLUSH_CHAR_THRESHOLD ||
                                event.textChunk.contains('\n') ||
                                (now - lastFlushAtMs) >= STREAM_FLUSH_INTERVAL_MS

                            if (shouldFlush) {
                                lastFlushAtMs = flushPendingAssistantText(
                                    messageId = assistantMessageId,
                                    visibleContent = visibleAssistantContent,
                                    pendingContent = pendingAssistantContent,
                                    timestampMs = now
                                )
                            }
                        }

                        is ChatStreamEvent.Completed -> {
                            val finalResponse = event.result.content
                            flushPendingAssistantText(
                                messageId = assistantMessageId,
                                visibleContent = visibleAssistantContent,
                                pendingContent = pendingAssistantContent,
                                timestampMs = System.currentTimeMillis()
                            )
                            conversationHistory.add(userHistoryMessage)
                            conversationHistory.add(
                                ChatMessage(
                                    role = ChatMessage.ROLE_ASSISTANT,
                                    content = finalResponse
                                )
                            )

                            updateUiMessage(
                                messageId = assistantMessageId,
                                content = finalResponse,
                                isError = false,
                                isStreaming = false,
                                renderMode = RenderMode.MARKDOWN,
                                showTypingDots = false
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                val errorMessage = error.message ?: "An error occurred"
                val replaced = updateUiMessage(
                    messageId = assistantMessageId,
                    content = errorMessage,
                    isError = true,
                    isStreaming = false,
                    renderMode = RenderMode.PLAIN,
                    showTypingDots = false
                )

                if (!replaced) {
                    appendUiMessage(
                        UiChatMessage(
                            id = assistantMessageId,
                            content = errorMessage,
                            isUser = false,
                            isError = true,
                            renderMode = RenderMode.PLAIN
                        )
                    )
                }
            }

            _isLoading.value = false
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        conversationHistory.clear()
        nextMessageId = 1L
    }

    private fun nextId(): Long = nextMessageId++

    private fun appendUiMessage(message: UiChatMessage) {
        val updatedMessages = _messages.value.orEmpty().toMutableList()
        updatedMessages.add(message)
        _messages.value = updatedMessages
    }

    private fun updateUiMessage(
        messageId: Long,
        content: String,
        isError: Boolean,
        isStreaming: Boolean,
        renderMode: RenderMode,
        showTypingDots: Boolean
    ): Boolean {
        val updatedMessages = _messages.value.orEmpty().toMutableList()
        val index = updatedMessages.indexOfFirst { it.id == messageId }
        if (index < 0) {
            return false
        }

        val existing = updatedMessages[index]
        updatedMessages[index] = existing.copy(
            content = content,
            isError = isError,
            isStreaming = isStreaming,
            renderMode = renderMode,
            showTypingDots = showTypingDots
        )
        _messages.value = updatedMessages
        return true
    }

    private fun flushPendingAssistantText(
        messageId: Long,
        visibleContent: StringBuilder,
        pendingContent: StringBuilder,
        timestampMs: Long
    ): Long {
        if (pendingContent.isEmpty()) {
            return timestampMs
        }

        visibleContent.append(pendingContent)
        pendingContent.clear()
        updateUiMessage(
            messageId = messageId,
            content = visibleContent.toString(),
            isError = false,
            isStreaming = true,
            renderMode = RenderMode.PLAIN,
            showTypingDots = false
        )
        return timestampMs
    }
}
