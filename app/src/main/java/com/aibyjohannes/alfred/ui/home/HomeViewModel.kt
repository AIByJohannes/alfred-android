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

data class UiChatMessage(
    val id: Long = 0L,
    val content: String,
    val isUser: Boolean,
    val isError: Boolean = false,
    val isStreaming: Boolean = false
)

class HomeViewModel : ViewModel() {

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
                isStreaming = true
            )
        )

        _isLoading.value = true

        viewModelScope.launch {
            val userHistoryMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = userInput)
            val streamedAssistantContent = StringBuilder()

            try {
                repo.streamMessage(userInput, conversationHistory).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            streamedAssistantContent.append(event.textChunk)
                            updateUiMessage(
                                messageId = assistantMessageId,
                                content = streamedAssistantContent.toString(),
                                isError = false,
                                isStreaming = true
                            )
                        }

                        is ChatStreamEvent.Completed -> {
                            val finalResponse = event.result.content
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
                                isStreaming = false
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
                    isStreaming = false
                )

                if (!replaced) {
                    appendUiMessage(
                        UiChatMessage(
                            id = assistantMessageId,
                            content = errorMessage,
                            isUser = false,
                            isError = true
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
        isStreaming: Boolean
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
            isStreaming = isStreaming
        )
        _messages.value = updatedMessages
        return true
    }
}
