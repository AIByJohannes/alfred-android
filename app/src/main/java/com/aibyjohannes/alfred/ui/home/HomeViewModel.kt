package com.aibyjohannes.alfred.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.launch

data class UiChatMessage(
    val content: String,
    val isUser: Boolean,
    val isError: Boolean = false
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

    fun initialize(apiKeyStore: ApiKeyStore, repository: ChatRepository, greetingMessage: String) {
        this.apiKeyStore = apiKeyStore
        this.repository = repository
        checkApiKey()
        
        // Initialize conversation with greeting message
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = greetingMessage
            ))
        }
    }

    fun checkApiKey() {
        _needsApiKey.value = apiKeyStore?.hasApiKey() != true
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return

        val repo = repository ?: return

        // Add user message to UI
        val currentMessages = _messages.value.orEmpty().toMutableList()
        currentMessages.add(UiChatMessage(content = userInput, isUser = true))
        _messages.value = currentMessages

        _isLoading.value = true

        viewModelScope.launch {
            val result = repo.sendMessage(userInput, conversationHistory)

            result.onSuccess { response ->
                // Add to conversation history
                conversationHistory.add(ChatMessage(role = ChatMessage.ROLE_USER, content = userInput))
                conversationHistory.add(ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = response))

                // Add assistant message to UI
                val updatedMessages = _messages.value.orEmpty().toMutableList()
                updatedMessages.add(UiChatMessage(content = response, isUser = false))
                _messages.value = updatedMessages
            }.onFailure { error ->
                val updatedMessages = _messages.value.orEmpty().toMutableList()
                updatedMessages.add(UiChatMessage(
                    content = error.message ?: "An error occurred",
                    isUser = false,
                    isError = true
                ))
                _messages.value = updatedMessages
            }

            _isLoading.value = false
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        conversationHistory.clear()
    }
}