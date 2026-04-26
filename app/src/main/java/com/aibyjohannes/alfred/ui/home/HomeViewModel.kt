package com.aibyjohannes.alfred.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationSummary
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

data class UiConversation(
    val id: Long,
    val title: String,
    val updatedAtEpochMs: Long
)

class HomeViewModel : ViewModel() {
    companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 60L
        private const val STREAM_FLUSH_CHAR_THRESHOLD = 24
    }

    private var repository: ChatRepository? = null
    private var apiKeyStore: ApiKeyStore? = null
    private var conversationStore: ConversationStore? = null

    private val _messages = MutableLiveData<List<UiChatMessage>>(emptyList())
    val messages: LiveData<List<UiChatMessage>> = _messages

    private val _conversations = MutableLiveData<List<UiConversation>>(emptyList())
    val conversations: LiveData<List<UiConversation>> = _conversations

    private val _activeConversationId = MutableLiveData<Long?>(null)
    val activeConversationId: LiveData<Long?> = _activeConversationId

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _needsApiKey = MutableLiveData(false)
    val needsApiKey: LiveData<Boolean> = _needsApiKey

    // Keep track of conversation history for context
    private val conversationHistory = mutableListOf<ChatMessage>()
    private var currentConversationId: Long? = null
    private var nextMessageId = 1L

    fun initialize(apiKeyStore: ApiKeyStore, repository: ChatRepository, conversationStore: ConversationStore) {
        if (this.apiKeyStore != null && this.repository != null && this.conversationStore != null) {
            checkApiKey()
            return
        }
        this.apiKeyStore = apiKeyStore
        this.repository = repository
        this.conversationStore = conversationStore
        checkApiKey()
        loadOrCreateActiveConversation()
    }

    fun checkApiKey() {
        _needsApiKey.value = apiKeyStore?.hasApiKey() != true
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return

        val repo = repository ?: return
        val store = conversationStore ?: return
        val conversationId = currentConversationId ?: return

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
                repo.streamMessage(userInput, conversationHistory.toList()).collect { event ->
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
                            store.appendMessage(
                                conversationId = conversationId,
                                role = ChatMessage.ROLE_USER,
                                content = userInput
                            )
                            store.appendMessage(
                                conversationId = conversationId,
                                role = ChatMessage.ROLE_ASSISTANT,
                                content = finalResponse
                            )
                            refreshConversationList()

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
        val store = conversationStore ?: return
        val activeId = currentConversationId ?: return
        viewModelScope.launch {
            store.deleteConversation(activeId)
            val newConversation = store.createConversation()
            loadConversation(newConversation)
            refreshConversationList()
        }
    }

    fun createConversationAndSwitch() {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val newConversation = store.createConversation()
            loadConversation(newConversation)
            refreshConversationList()
        }
    }

    fun selectConversation(conversationId: Long) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val selectedConversation = store.switchActiveConversation(conversationId)
            loadConversation(selectedConversation)
            refreshConversationList()
        }
    }

    private fun loadOrCreateActiveConversation() {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val activeConversation = store.getOrCreateActiveConversation()
            loadConversation(activeConversation)
            refreshConversationList()
        }
    }

    private suspend fun loadConversation(conversation: ConversationSummary) {
        val store = conversationStore ?: return
        currentConversationId = conversation.id
        _activeConversationId.postValue(conversation.id)

        val storedMessages = store.loadMessages(conversation.id)
        val uiMessages = storedMessages.map { stored ->
            UiChatMessage(
                id = stored.id,
                content = stored.content,
                isUser = stored.role == ChatMessage.ROLE_USER
            )
        }
        _messages.postValue(uiMessages)

        conversationHistory.clear()
        conversationHistory.addAll(
            storedMessages.map { stored ->
                ChatMessage(
                    role = stored.role,
                    content = stored.content
                )
            }
        )

        nextMessageId = (uiMessages.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    private suspend fun refreshConversationList() {
        val store = conversationStore ?: return
        val list = store.listConversations().map { summary ->
            UiConversation(
                id = summary.id,
                title = summary.title?.takeIf { it.isNotBlank() } ?: "Conversation ${summary.id}",
                updatedAtEpochMs = summary.updatedAtEpochMs
            )
        }
        _conversations.postValue(list)
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
