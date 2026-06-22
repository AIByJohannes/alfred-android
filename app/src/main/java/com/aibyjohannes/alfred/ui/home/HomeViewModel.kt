package com.aibyjohannes.alfred.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.SysInfoProvider
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationMessageDraft
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationSummary
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RenderMode {
    PLAIN,
    MARKDOWN
}

enum class VoiceModeState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

data class UiChatMessage(
    val id: Long = 0L,
    val content: String,
    val isUser: Boolean,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val renderMode: RenderMode = RenderMode.MARKDOWN,
    val showTypingDots: Boolean = false,
    val traceItems: List<UiTraceItem> = emptyList()
)

enum class UiTraceKind {
    ASSISTANT_DRAFT,
    REASONING,
    TOOL_CALL,
    TOOL_RESULT
}

data class UiTraceItem(
    val id: String,
    val kind: UiTraceKind,
    val title: String,
    val content: String,
    val isError: Boolean = false,
    val isExpanded: Boolean = false
)

data class UiConversation(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long
)

data class UiWorkspace(
    val id: String,
    val name: String
)

class HomeViewModel : ViewModel() {
    companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 60L
        private const val STREAM_FLUSH_CHAR_THRESHOLD = 24
    }

    private var repository: ChatRepository? = null
    private var apiKeyStore: ApiKeyStore? = null
    private var conversationStore: ConversationStore? = null
    private var sysInfoProvider: SysInfoProvider? = null
    private var onChatActivity: (() -> Unit)? = null
    private var nextTurnMaxPasses: Int? = null
    private val newChatMutex = Mutex()

    private val _messages = MutableLiveData<List<UiChatMessage>>(emptyList())
    val messages: LiveData<List<UiChatMessage>> = _messages

    private val _conversations = MutableLiveData<List<UiConversation>>(emptyList())
    val conversations: LiveData<List<UiConversation>> = _conversations

    private val _activeConversationId = MutableLiveData<String?>(null)
    val activeConversationId: LiveData<String?> = _activeConversationId

    private val _workspaces = MutableLiveData<List<UiWorkspace>>(emptyList())
    val workspaces: LiveData<List<UiWorkspace>> = _workspaces

    private val _activeWorkspaceId = MutableLiveData<String?>(null)
    val activeWorkspaceId: LiveData<String?> = _activeWorkspaceId

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _needsApiKey = MutableLiveData(false)
    val needsApiKey: LiveData<Boolean> = _needsApiKey

    private val _storageError = MutableLiveData<String?>(null)
    val storageError: LiveData<String?> = _storageError

    private val _voiceModeState = MutableLiveData(VoiceModeState.IDLE)
    val voiceModeState: LiveData<VoiceModeState> = _voiceModeState

    private val _ttsAudioFile = MutableLiveData<java.io.File?>(null)
    val ttsAudioFile: LiveData<java.io.File?> = _ttsAudioFile

    /** Set to true when the next completed message should trigger TTS. */
    private var voiceModeActive = false

    // Keep track of conversation history for context
    private val conversationHistory = mutableListOf<ChatMessage>()
    private var currentConversationId: String? = null
    private var nextMessageId = 1L
    private var conversationsJob: kotlinx.coroutines.Job? = null

    fun initialize(
        apiKeyStore: ApiKeyStore,
        repository: ChatRepository,
        conversationStore: ConversationStore,
        sysInfoProvider: SysInfoProvider? = null,
        onChatActivity: (() -> Unit)? = null
    ) {
        if (this.apiKeyStore != null && this.repository != null && this.conversationStore != null) {
            this.sysInfoProvider = sysInfoProvider
            this.onChatActivity = onChatActivity
            checkApiKey()
            return
        }
        this.apiKeyStore = apiKeyStore
        this.repository = repository
        this.conversationStore = conversationStore
        this.sysInfoProvider = sysInfoProvider
        this.onChatActivity = onChatActivity
        checkApiKey()
        loadWorkspacesAndActiveConversation()
    }

    fun checkApiKey() {
        _needsApiKey.value = apiKeyStore?.hasApiKey() != true
    }

    fun retryChatHistoryLoad() {
        loadWorkspacesAndActiveConversation()
    }

    fun consumeStorageError() {
        _storageError.value = null
    }

    suspend fun transcribeAudio(audioFile: java.io.File): Result<String> {
        val repo = repository ?: return Result.failure(Exception("Repository not initialized"))
        return repo.transcribeAudio(audioFile)
    }

    suspend fun synthesizeSpeech(text: String, cacheDir: java.io.File): Result<java.io.File> {
        val repo = repository ?: return Result.failure(Exception("Repository not initialized"))
        val outputFile = java.io.File(cacheDir, "tts_response_${System.currentTimeMillis()}.mp3")
        return repo.synthesizeSpeech(text, outputFile)
    }

    fun setVoiceModeState(state: VoiceModeState) {
        _voiceModeState.value = state
        voiceModeActive = state != VoiceModeState.IDLE
    }

    fun emitTtsFile(file: java.io.File?) {
        _ttsAudioFile.value = file
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return

        val repo = repository ?: return
        val store = conversationStore ?: return
        val conversationId = currentConversationId ?: return

        val maxPasses = nextTurnMaxPasses
        nextTurnMaxPasses = null

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
                renderMode = RenderMode.MARKDOWN,
                showTypingDots = false
            )
        )

        _isLoading.value = true

        val sysInfo = sysInfoProvider?.buildSysInfo()

        viewModelScope.launch {
            val userHistoryMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = userInput)
            val passAssistantContent = StringBuilder()
            val pendingAssistantContent = StringBuilder()
            val traceItems = mutableListOf<UiTraceItem>()
            var lastFlushAtMs = 0L

            try {
                repo.streamMessage(userInput, conversationHistory.toList(), sysInfo, maxPasses).collect { event ->
                    when (event) {
                        is ChatStreamEvent.PassStarted -> {
                            passAssistantContent.clear()
                            pendingAssistantContent.clear()
                        }

                        is ChatStreamEvent.TextDelta -> {
                            pendingAssistantContent.append(event.textChunk)
                            val now = System.currentTimeMillis()
                            val shouldFlush = pendingAssistantContent.length >= STREAM_FLUSH_CHAR_THRESHOLD ||
                                event.textChunk.contains('\n') ||
                                (now - lastFlushAtMs) >= STREAM_FLUSH_INTERVAL_MS

                            if (shouldFlush) {
                                lastFlushAtMs = flushPendingAssistantText(
                                    messageId = assistantMessageId,
                                    visibleContent = passAssistantContent,
                                    pendingContent = pendingAssistantContent,
                                    timestampMs = now
                                )
                            }
                        }

                        is ChatStreamEvent.ReasoningDelta -> {
                            val id = "reasoning-${event.passIndex}"
                            val text = event.summaryChunk ?: event.textChunk
                            if (!text.isNullOrBlank()) {
                                upsertTraceItem(
                                    messageId = assistantMessageId,
                                    traceItems = traceItems,
                                    item = UiTraceItem(
                                        id = id,
                                        kind = UiTraceKind.REASONING,
                                        title = "Reasoning",
                                        content = text,
                                        isExpanded = true
                                    ),
                                    appendContent = true
                                )
                            }
                        }

                        is ChatStreamEvent.ReasoningComplete -> {
                            val id = event.id ?: "reasoning-${event.passIndex}"
                            val completedContent = event.summary.joinToString("\n").ifBlank {
                                event.content.joinToString("\n")
                            }.ifBlank {
                                if (event.encrypted.isNullOrBlank()) "" else "Reasoning preserved for continuity."
                            }
                            val existingContent = traceItems
                                .firstOrNull { it.id == id && it.kind == UiTraceKind.REASONING }
                                ?.content
                            val content = preferFullerTraceContent(
                                existingContent = existingContent,
                                completedContent = completedContent
                            )
                            if (content.isNotBlank()) {
                                upsertTraceItem(
                                    messageId = assistantMessageId,
                                    traceItems = traceItems,
                                    item = UiTraceItem(
                                        id = id,
                                        kind = UiTraceKind.REASONING,
                                        title = "Reasoning",
                                        content = content,
                                        isExpanded = false
                                    ),
                                    appendContent = false
                                )
                            }
                        }

                        is ChatStreamEvent.ToolCallDelta -> {
                            val id = event.id 
                                ?: traceItems.lastOrNull { it.kind == UiTraceKind.TOOL_CALL }?.id 
                                ?: "tool-${event.passIndex}"
                            val title = event.name?.let { "Calling $it" } ?: "Tool call"
                            upsertTraceItem(
                                messageId = assistantMessageId,
                                traceItems = traceItems,
                                item = UiTraceItem(
                                    id = id,
                                    kind = UiTraceKind.TOOL_CALL,
                                    title = title,
                                    content = event.argumentsChunk,
                                    isExpanded = true
                                ),
                                appendContent = true
                            )
                        }

                        is ChatStreamEvent.ToolCallRequested -> {
                            flushPendingAssistantText(
                                messageId = assistantMessageId,
                                visibleContent = passAssistantContent,
                                pendingContent = pendingAssistantContent,
                                timestampMs = System.currentTimeMillis()
                            )
                            if (passAssistantContent.isNotBlank()) {
                                traceItems.add(
                                    UiTraceItem(
                                        id = "draft-${event.passIndex}",
                                        kind = UiTraceKind.ASSISTANT_DRAFT,
                                        title = "Intermediate answer",
                                        content = passAssistantContent.toString()
                                    )
                                )
                                passAssistantContent.clear()
                            }
                            upsertTraceItem(
                                messageId = assistantMessageId,
                                traceItems = traceItems,
                                item = UiTraceItem(
                                    id = event.toolCallId ?: "tool-${event.passIndex}-${event.name}",
                                    kind = UiTraceKind.TOOL_CALL,
                                    title = "Called ${event.name}",
                                    content = event.argumentsJson
                                ),
                                appendContent = false,
                                contentOverride = ""
                            )
                        }

                        is ChatStreamEvent.ToolResultAvailable -> {
                            upsertTraceItem(
                                messageId = assistantMessageId,
                                traceItems = traceItems,
                                item = UiTraceItem(
                                    id = "result-${event.toolCallId ?: "${event.passIndex}-${event.name}"}",
                                    kind = UiTraceKind.TOOL_RESULT,
                                    title = "Result from ${event.name}",
                                    content = event.resultPreview,
                                    isError = event.isError
                                ),
                                appendContent = false
                            )
                        }

                        is ChatStreamEvent.PassCompleted -> Unit

                        is ChatStreamEvent.Completed -> {
                            val finalResponse = event.result.content
                            flushPendingAssistantText(
                                messageId = assistantMessageId,
                                visibleContent = passAssistantContent,
                                pendingContent = pendingAssistantContent,
                                timestampMs = System.currentTimeMillis()
                            )
                            val turnMessages = listOf(userHistoryMessage) + event.result.intermediateMessages.map { core ->
                                ChatMessage(
                                    role = core.role,
                                    content = core.content,
                                    kind = when (core.kind.name) {
                                        "REASONING" -> ChatMessage.KIND_REASONING
                                        "TOOL_CALL" -> ChatMessage.KIND_TOOL_CALL
                                        "TOOL_RESULT" -> ChatMessage.KIND_TOOL_RESULT
                                        else -> ChatMessage.KIND_MESSAGE
                                    },
                                    turnId = core.turnId,
                                    toolCallId = core.toolCallId,
                                    toolName = core.toolName,
                                    toolArgumentsJson = core.toolArgumentsJson,
                                    isError = core.isError,
                                    reasoningText = core.reasoningText,
                                    reasoningSummary = core.reasoningSummary,
                                    encryptedReasoning = core.encryptedReasoning,
                                    includeInPrompt = core.includeInPrompt,
                                    searchable = core.searchable
                                )
                            }
                            conversationHistory.addAll(turnMessages)
                            store.appendMessages(
                                conversationId = conversationId,
                                messages = turnMessages.map {
                                    ConversationMessageDraft(
                                        role = it.role,
                                        content = it.content,
                                        kind = it.kind,
                                        turnId = it.turnId,
                                        toolCallId = it.toolCallId,
                                        toolName = it.toolName,
                                        toolArgumentsJson = it.toolArgumentsJson,
                                        isError = it.isError,
                                        reasoningText = it.reasoningText,
                                        reasoningSummary = it.reasoningSummary,
                                        encryptedReasoning = it.encryptedReasoning,
                                        includeInPrompt = it.includeInPrompt,
                                        searchable = it.searchable
                                    )
                                }
                            )
                            onChatActivity?.invoke()

                            updateUiMessage(
                                messageId = assistantMessageId,
                                content = finalResponse,
                                isError = false,
                                isStreaming = false,
                                renderMode = RenderMode.MARKDOWN,
                                showTypingDots = false,
                                traceItems = traceItems.toList()
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

    fun allowMoreLoops(count: Int) {
        nextTurnMaxPasses = count
        sendMessage("continue")
    }

    private fun observeConversationsForActiveWorkspace(workspaceId: String) {
        conversationsJob?.cancel()
        val store = conversationStore ?: return
        conversationsJob = viewModelScope.launch {
            store.observeConversations(workspaceId).collect { list ->
                val uiList = list.map { summary ->
                    UiConversation(
                        id = summary.id,
                        title = summary.title?.takeIf { it.isNotBlank() } ?: "Conversation ${summary.id}",
                        updatedAtEpochMs = summary.updatedAtEpochMs
                    )
                }
                _conversations.postValue(uiList)
            }
        }
    }

    fun clearChat() {
        val store = conversationStore ?: return
        val activeId = currentConversationId ?: return
        viewModelScope.launch {
            store.deleteConversation(activeId)
            val newConversation = store.createConversation()
            loadConversation(newConversation)
        }
    }

    fun deleteConversation(conversationId: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            store.deleteConversation(conversationId)

            if (currentConversationId == conversationId) {
                val remaining = store.listConversations()
                if (remaining.isNotEmpty()) {
                    val nextConversation = remaining.maxByOrNull { it.updatedAtEpochMs }!!
                    val switched = store.switchActiveConversation(nextConversation.id)
                    loadConversation(switched)
                } else {
                    val newConversation = store.createConversation()
                    loadConversation(newConversation)
                }
            }
        }
    }

    fun createConversationAndSwitch() {
        val store = conversationStore ?: return
        if (_isLoading.value == true) return
        viewModelScope.launch {
            newChatMutex.withLock {
                if (_isLoading.value == true) return@withLock
                val currentId = currentConversationId
                if (currentId != null && store.loadMessages(currentId).isEmpty()) {
                    // Already in an empty persisted conversation, keep using it.
                    return@withLock
                }
                val newConversation = store.createConversation()
                loadConversation(newConversation)
            }
        }
    }

    fun selectConversation(conversationId: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            try {
                val selectedConversation = store.switchActiveConversation(conversationId)
                loadConversation(selectedConversation)
            } catch (_: IllegalArgumentException) {
                val activeConversation = store.getOrCreateActiveConversation()
                loadConversation(activeConversation)
            }
        }
    }

    fun createWorkspace(name: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val newWs = store.createWorkspace(name)
            val activeConversation = store.getOrCreateActiveConversation()
            loadConversation(activeConversation)
            _activeWorkspaceId.value = newWs.id
            refreshWorkspacesList()
            observeConversationsForActiveWorkspace(newWs.id)
        }
    }

    fun switchWorkspace(workspaceId: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val switchedWs = store.switchActiveWorkspace(workspaceId)
            val activeConversation = store.getOrCreateActiveConversation()
            loadConversation(activeConversation)
            _activeWorkspaceId.value = switchedWs.id
            refreshWorkspacesList()
            observeConversationsForActiveWorkspace(switchedWs.id)
        }
    }

    fun renameWorkspace(workspaceId: String, newName: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            store.renameWorkspace(workspaceId, newName)
            refreshWorkspacesList()
            val activeWs = store.getOrCreateActiveWorkspace()
            _activeWorkspaceId.value = activeWs.id
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val currentList = store.listWorkspaces()
            if (currentList.size <= 1) return@launch
            
            store.deleteWorkspace(workspaceId)
            
            val activeWs = store.getOrCreateActiveWorkspace()
            _activeWorkspaceId.value = activeWs.id
            refreshWorkspacesList()

            val activeConversation = store.getOrCreateActiveConversation()
            loadConversation(activeConversation)
            observeConversationsForActiveWorkspace(activeWs.id)
        }
    }

    private suspend fun refreshWorkspacesList() {
        val store = conversationStore ?: return
        val list = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
        _workspaces.postValue(list)
    }

    private fun loadWorkspacesAndActiveConversation() {
        val store = conversationStore ?: return
        viewModelScope.launch {
            try {
                val activeWs = store.getOrCreateActiveWorkspace()
                _activeWorkspaceId.value = activeWs.id

                val wsList = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
                _workspaces.value = wsList

                val activeConversation = store.getOrCreateActiveConversation()
                loadConversation(activeConversation)
                observeConversationsForActiveWorkspace(activeWs.id)
                _storageError.value = null
            } catch (error: Exception) {
                _storageError.value = error.message ?: "Chat history is temporarily unavailable"
            }
        }
    }

    private fun loadOrCreateActiveConversation() {
        val store = conversationStore ?: return
        viewModelScope.launch {
            val activeWs = store.getOrCreateActiveWorkspace()
            val activeConversation = store.getOrCreateActiveConversation()
            loadConversation(activeConversation)
            observeConversationsForActiveWorkspace(activeWs.id)
        }
    }

    private suspend fun loadConversation(conversation: ConversationSummary) {
        val store = conversationStore ?: return
        currentConversationId = conversation.id
        _activeConversationId.postValue(conversation.id)

        val storedMessages = store.loadMessages(conversation.id)
        val pendingTraceByTurn = mutableMapOf<String, MutableList<UiTraceItem>>()
        val uiMessages = storedMessages.mapNotNull { stored ->
            if (!stored.searchable && stored.turnId != null) {
                pendingTraceByTurn.getOrPut(stored.turnId) { mutableListOf() }.add(stored.toTraceItem())
                null
            } else {
                UiChatMessage(
                    id = stored.id,
                    content = stored.content,
                    isUser = stored.role == ChatMessage.ROLE_USER,
                    traceItems = stored.turnId?.let { pendingTraceByTurn.remove(it) }.orEmpty()
                )
            }
        }
        _messages.postValue(uiMessages)

        conversationHistory.clear()
        conversationHistory.addAll(
            storedMessages.map { stored ->
                ChatMessage(
                    role = stored.role,
                    content = stored.content,
                    kind = stored.kind,
                    turnId = stored.turnId,
                    toolCallId = stored.toolCallId,
                    toolName = stored.toolName,
                    toolArgumentsJson = stored.toolArgumentsJson,
                    isError = stored.isError,
                    reasoningText = stored.reasoningText,
                    reasoningSummary = stored.reasoningSummary,
                    encryptedReasoning = stored.encryptedReasoning,
                    includeInPrompt = stored.includeInPrompt,
                    searchable = stored.searchable
                )
            }
        )

        nextMessageId = (uiMessages.maxOfOrNull { it.id } ?: 0L) + 1L
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
        showTypingDots: Boolean,
        traceItems: List<UiTraceItem>? = null
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
            showTypingDots = showTypingDots,
            traceItems = traceItems ?: existing.traceItems
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
            renderMode = RenderMode.MARKDOWN,
            showTypingDots = false
        )
        return timestampMs
    }

    private fun preferFullerTraceContent(existingContent: String?, completedContent: String): String {
        val existing = existingContent?.takeIf { it.isNotBlank() } ?: return completedContent
        if (completedContent.isBlank()) {
            return existing
        }
        return if (existing.trim().length > completedContent.trim().length) {
            existing
        } else {
            completedContent
        }
    }

    private fun upsertTraceItem(
        messageId: Long,
        traceItems: MutableList<UiTraceItem>,
        item: UiTraceItem,
        appendContent: Boolean,
        contentOverride: String? = null
    ) {
        val index = traceItems.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            val existing = traceItems[index]
            traceItems[index] = item.copy(
                content = if (appendContent) existing.content + item.content else item.content
            )
        } else {
            traceItems.add(item)
        }

        updateUiMessage(
            messageId = messageId,
            content = contentOverride ?: _messages.value.orEmpty().firstOrNull { it.id == messageId }?.content.orEmpty(),
            isError = false,
            isStreaming = true,
            renderMode = RenderMode.MARKDOWN,
            showTypingDots = false,
            traceItems = traceItems.toList()
        )
    }

    private fun com.aibyjohannes.alfred.data.local.StoredChatMessage.toTraceItem(): UiTraceItem {
        return when (kind) {
            ChatMessage.KIND_REASONING -> UiTraceItem(
                id = id.toString(),
                kind = UiTraceKind.REASONING,
                title = "Reasoning",
                content = reasoningSummary ?: reasoningText ?: content
            )
            ChatMessage.KIND_TOOL_CALL -> UiTraceItem(
                id = id.toString(),
                kind = UiTraceKind.TOOL_CALL,
                title = toolName?.let { "Called $it" } ?: "Tool call",
                content = toolArgumentsJson ?: content
            )
            ChatMessage.KIND_TOOL_RESULT -> UiTraceItem(
                id = id.toString(),
                kind = UiTraceKind.TOOL_RESULT,
                title = toolName?.let { "Result from $it" } ?: "Tool result",
                content = content.take(400),
                isError = isError
            )
            else -> UiTraceItem(
                id = id.toString(),
                kind = UiTraceKind.ASSISTANT_DRAFT,
                title = "Intermediate answer",
                content = content
            )
        }
    }
}
