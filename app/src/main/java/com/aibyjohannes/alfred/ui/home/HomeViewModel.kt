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
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

class HomeViewModel(private val messagePreparationDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 60L
        private const val STREAM_FLUSH_CHAR_THRESHOLD = 24
    }

    private var repository: ChatRepository? = null
    private var apiKeyStore: ApiKeyStore? = null
    private var conversationStore: ConversationStore? = null
    private var sysInfoProvider: SysInfoProvider? = null
    private var onChatActivity: (() -> Unit)? = null
    private var workspaceDeletionHandler: suspend (String) -> Unit = {}
    private var chatRunPowerKeeper: ChatRunPowerKeeper = NoOpChatRunPowerKeeper
    private var nextTurnMaxPasses: Int? = null
    private val navigationMutex = Mutex()
    private val navigationJobs = mutableSetOf<Job>()
    private var reconciliationJob: Job? = null

    private val _messages = MutableLiveData<List<UiChatMessage>>(emptyList())
    val messages: LiveData<List<UiChatMessage>> = _messages

    private val _conversations = MutableLiveData<List<UiConversation>>(emptyList())
    val conversations: LiveData<List<UiConversation>> = _conversations

    private val _isConversationListLoading = MutableLiveData(true)
    /** True until the active workspace's conversation list has emitted from storage. */
    val isConversationListLoading: LiveData<Boolean> = _isConversationListLoading

    private val _activeConversationId = MutableLiveData<String?>(null)
    val activeConversationId: LiveData<String?> = _activeConversationId

    private val _workspaces = MutableLiveData<List<UiWorkspace>>(emptyList())
    val workspaces: LiveData<List<UiWorkspace>> = _workspaces

    private val _activeWorkspaceId = MutableLiveData<String?>(null)
    val activeWorkspaceId: LiveData<String?> = _activeWorkspaceId

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isConversationLoading = MutableLiveData(false)
    /** True while a file-backed conversation or workspace change is in progress. */
    val isConversationLoading: LiveData<Boolean> = _isConversationLoading

    private val _deletingConversationIds = MutableLiveData<Set<String>>(emptySet())
    val deletingConversationIds: LiveData<Set<String>> = _deletingConversationIds

    private val _needsApiKey = MutableLiveData(false)
    val needsApiKey: LiveData<Boolean> = _needsApiKey

    private val _storageError = MutableLiveData<String?>(null)
    val storageError: LiveData<String?> = _storageError

    private val _voiceModeState = MutableLiveData(VoiceModeState.IDLE)
    val voiceModeState: LiveData<VoiceModeState> = _voiceModeState

    private val _ttsAudioFile = MutableLiveData<java.io.File?>(null)
    val ttsAudioFile: LiveData<java.io.File?> = _ttsAudioFile

    private val _sharedText = MutableLiveData<String?>(null)
    val sharedText: LiveData<String?> = _sharedText

    /** Set to true when the next completed message should trigger TTS. */
    private var voiceModeActive = false

    // Keep track of conversation history for context
    private val conversationHistory = mutableListOf<ChatMessage>()
    private var currentConversationId: String? = null
    private var nextMessageId = 1L
    private var conversationsJob: kotlinx.coroutines.Job? = null
    private var conversationListLoadGeneration = 0L
    private var chatJob: Job? = null
    private var pendingConversationOperations = 0
    private var conversationReady = false
    private var historyGeneration = 0L
    private var replacingHistory = false

    private data class NavigationState(
        val conversationId: String?,
        val workspaceId: String?,
        val messages: List<UiChatMessage>,
        val history: List<ChatMessage>,
        val nextMessageId: Long,
        val workspaces: List<UiWorkspace>,
        val ready: Boolean
    )

    private data class LoadedConversation(
        val id: String,
        val messages: List<UiChatMessage>,
        val history: List<ChatMessage>,
        val nextMessageId: Long
    )

    fun initialize(
        apiKeyStore: ApiKeyStore,
        repository: ChatRepository,
        conversationStore: ConversationStore,
        sysInfoProvider: SysInfoProvider? = null,
        onChatActivity: (() -> Unit)? = null,
        chatRunPowerKeeper: ChatRunPowerKeeper = NoOpChatRunPowerKeeper,
        workspaceDeletionHandler: suspend (String) -> Unit = {},
        startWithNewConversation: Boolean = false
    ) {
        val previousStore = this.conversationStore
        val previousIdentity = previousStore?.storageIdentity
        val nextIdentity = conversationStore.storageIdentity
        val sameRoot = previousStore != null && (
            (previousIdentity != null && nextIdentity != null && previousIdentity == nextIdentity) ||
                (previousIdentity == null && nextIdentity == null && previousStore === conversationStore)
            )
        if (this.apiKeyStore != null && this.repository != null && sameRoot) {
            this.sysInfoProvider = sysInfoProvider
            this.onChatActivity = onChatActivity
            this.chatRunPowerKeeper = chatRunPowerKeeper
            this.workspaceDeletionHandler = workspaceDeletionHandler
            checkApiKey()
            return
        }
        val generation = ++historyGeneration
        replacingHistory = true
        conversationReady = false
        val obsoleteJobs = navigationJobs.toList() +
            listOfNotNull(reconciliationJob, conversationsJob, chatJob)
        navigationJobs.clear()
        obsoleteJobs.forEach { it.cancel() }
        conversationsJob = null
        chatJob = null
        reconciliationJob = null
        conversationListLoadGeneration++
        pendingConversationOperations = 0
        currentConversationId = null
        conversationHistory.clear()
        nextMessageId = 1L
        nextTurnMaxPasses = null
        _activeConversationId.value = null
        _activeWorkspaceId.value = null
        _messages.value = emptyList()
        _conversations.value = emptyList()
        _workspaces.value = emptyList()
        _deletingConversationIds.value = emptySet()
        _storageError.value = null
        _isLoading.value = false
        _isConversationListLoading.value = true
        this.apiKeyStore = apiKeyStore
        this.repository = repository
        this.conversationStore = conversationStore
        this.sysInfoProvider = sysInfoProvider
        this.onChatActivity = onChatActivity
        this.chatRunPowerKeeper = chatRunPowerKeeper
        this.workspaceDeletionHandler = workspaceDeletionHandler
        checkApiKey()
        pendingConversationOperations++
        _isConversationLoading.value = true
        val adoption = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                obsoleteJobs.forEach { it.join() }
                navigationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (generation != historyGeneration) return@withLock
                    stageActiveContext(
                        conversationStore,
                        startWithNewConversation = startWithNewConversation
                    )
                    launchHistoryReconciliation(conversationStore, generation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == historyGeneration) {
                    conversationReady = false
                    reportNavigationError(null, error)
                }
            } finally {
                if (generation == historyGeneration) replacingHistory = false
            }
        }
        trackNavigationJob(adoption, generation)
        adoption.start()
    }

    fun checkApiKey() {
        val store = apiKeyStore
        val model = store?.loadModel()
        _needsApiKey.value = when {
            model == null || model == LocalGemmaModelStore.LOCAL_MODEL_ID -> false
            else -> store.hasApiKey().not()
        }
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

    fun setSharedText(text: String) {
        _sharedText.value = text
    }

    fun consumeSharedText() {
        _sharedText.value = null
    }

    fun retryMessage(failedMessageId: Long) {
        if (!canSendMessage()) return
        val currentList = _messages.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == failedMessageId }
        if (index < 0) return
        val failedMsg = currentList[index]
        if (!failedMsg.isError) return

        // Find the user message associated with this failed run (normally the one before it)
        var userMsgContent = ""
        var userMsgIndex = -1
        for (i in index - 1 downTo 0) {
            if (currentList[i].isUser) {
                userMsgContent = currentList[i].content
                userMsgIndex = i
                break
            }
        }

        if (userMsgContent.isBlank()) return

        // Remove the failed assistant message and the user message from the UI list to avoid duplication
        if (userMsgIndex >= 0) {
            currentList.removeAt(index) // Remove failed assistant message
            currentList.removeAt(userMsgIndex) // Remove user message
            _messages.value = currentList
        }

        // Resend the message
        sendMessage(userMsgContent)
    }

    fun sendMessage(userInput: String, requestInput: String = userInput) {
        if (userInput.isBlank()) return
        if (!canSendMessage()) return

        val repo = repository ?: return
        val store = conversationStore ?: return
        val conversationId = currentConversationId ?: return
        val generation = historyGeneration
        val runPowerKeeper = chatRunPowerKeeper
        val activityCallback = onChatActivity
        val promptHistory = conversationHistory.toList()

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
        runPowerKeeper.acquire()

        val sysInfo = sysInfoProvider?.buildSysInfo()
        var powerReleased = false

        chatJob = viewModelScope.launch {
            val userHistoryMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = userInput)
            val passAssistantContent = StringBuilder()
            val pendingAssistantContent = StringBuilder()
            val traceItems = mutableListOf<UiTraceItem>()
            var lastFlushAtMs = 0L

            try {
                currentCoroutineContext().ensureActive()
                repo.streamMessage(
                    requestInput,
                    promptHistory,
                    sysInfo,
                    maxPasses,
                    sessionId = conversationId
                ).collect { event ->
                    currentCoroutineContext().ensureActive()
                    if (generation != historyGeneration) return@collect
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
                            currentCoroutineContext().ensureActive()
                            if (generation != historyGeneration) return@collect
                            activityCallback?.invoke()

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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != historyGeneration) return@launch
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

            finally {
                runPowerKeeper.release()
                powerReleased = true
                if (generation == historyGeneration) {
                    _isLoading.value = false
                    chatJob = null
                }
            }
        }
        // A reserved run can be canceled before its coroutine enters the try/finally.
        chatJob?.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!powerReleased) {
                    runPowerKeeper.release()
                    powerReleased = true
                }
                if (generation == historyGeneration) {
                    _isLoading.value = false
                    chatJob = null
                }
            }
        }
    }

    fun sendImageGenerationRequest(prompt: String) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) return
        sendMessage(
            userInput = "Generate an image of $trimmedPrompt",
            requestInput = "Generate an image of the following. You must call GenerateImageTool with a detailed prompt: $trimmedPrompt"
        )
    }

    fun stopGeneration() {
        if (_isLoading.value != true) return
        chatJob?.cancel()
        _messages.value = _messages.value.orEmpty().map { message ->
            if (message.isStreaming) {
                message.copy(
                    content = message.content.ifBlank { "Generation stopped." },
                    isStreaming = false,
                    isError = false,
                    showTypingDots = false
                )
            } else message
        }
    }

    suspend fun generateImage(prompt: String, cacheDir: java.io.File): Result<java.io.File> {
        val repo = repository ?: return Result.failure(Exception("Repository not initialized"))
        val outputFile = java.io.File(cacheDir, "generated_image_${System.currentTimeMillis()}.png")
        return repo.generateImage(prompt, outputFile)
    }

    fun allowMoreLoops(count: Int) {
        if (!canSendMessage()) return
        nextTurnMaxPasses = count
        sendMessage("continue")
    }

    private fun observeConversationsForActiveWorkspace(store: ConversationStore, workspaceId: String) {
        conversationsJob?.cancel()
        val history = historyGeneration
        val generation = ++conversationListLoadGeneration
        _isConversationListLoading.value = true
        _conversations.value = emptyList()
        conversationsJob = viewModelScope.launch {
            try {
                store.observeConversations(workspaceId).collect { list ->
                    val uiList = list.map { summary ->
                        UiConversation(
                            id = summary.id,
                            title = summary.title?.takeIf { it.isNotBlank() } ?: "Conversation ${summary.id}",
                            updatedAtEpochMs = summary.updatedAtEpochMs
                        )
                    }
                    if (history == historyGeneration && generation == conversationListLoadGeneration) {
                        _conversations.value = uiList
                        _isConversationListLoading.value = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (history == historyGeneration && generation == conversationListLoadGeneration) {
                    _isConversationListLoading.value = false
                    _storageError.value =
                        error.message ?: "Chat history is temporarily unavailable"
                }
            }
        }
    }

    private fun canSendMessage(): Boolean =
        conversationReady && !replacingHistory &&
            _isConversationLoading.value != true && _isLoading.value != true

    fun deleteConversation(conversationId: String) {
        if (conversationId in _deletingConversationIds.value.orEmpty()) return
        val generation = historyGeneration
        val job = launchNavigation(
            errorPrefix = "Couldn't delete the conversation",
            beforeStart = {
                _deletingConversationIds.value = _deletingConversationIds.value.orEmpty() + conversationId
            }
        ) { store ->
            val deletingActive = currentConversationId == conversationId
            store.deleteConversation(conversationId)
            currentCoroutineContext().ensureActive()
            if (generation != historyGeneration) return@launchNavigation
            if (deletingActive) {
                // The old context is no longer sendable, even if loading its survivor fails.
                conversationReady = false
                try {
                    val remaining = store.listConversations()
                    val survivor = remaining.maxByOrNull { it.updatedAtEpochMs }?.let {
                        store.switchActiveConversation(it.id)
                    } ?: store.createConversation()
                    stageActiveContext(store, conversation = survivor)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (generation == historyGeneration) {
                        reportNavigationError("Couldn't delete the conversation", error)
                    }
                }
            }
        } ?: return
        job.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (generation == historyGeneration) {
                    _deletingConversationIds.value = _deletingConversationIds.value.orEmpty() - conversationId
                }
            }
        }
    }

    fun requestNewChat() {
        if (!canSendMessage() || pendingConversationOperations > 0) return
        if (currentConversationId == null) return
        if (conversationHistory.isEmpty() && _messages.value.orEmpty().isEmpty()) return
        val previous = captureNavigationState()
        launchNavigation(
            errorPrefix = "Couldn't create a new chat",
            previousState = previous,
            beforeStart = { _messages.value = emptyList() }
        ) { store ->
            stageActiveContext(store, conversation = store.createConversation())
        }
    }

    fun selectConversation(conversationId: String) {
        launchNavigation { store ->
            val selected = try {
                store.switchActiveConversation(conversationId)
            } catch (_: IllegalArgumentException) {
                store.getOrCreateActiveConversation()
            }
            stageActiveContext(store, conversation = selected)
        }
    }

    fun createWorkspace(name: String) {
        launchNavigation { store ->
            val workspace = store.createWorkspace(name)
            stageActiveContext(store, workspace = workspace)
        }
    }

    fun switchWorkspace(workspaceId: String) {
        launchNavigation { store ->
            val workspace = store.switchActiveWorkspace(workspaceId)
            stageActiveContext(store, workspace = workspace)
        }
    }

    fun renameWorkspace(workspaceId: String, newName: String) {
        val generation = historyGeneration
        launchNavigation { store ->
            store.renameWorkspace(workspaceId, newName)
            val workspace = store.getOrCreateActiveWorkspace()
            val list = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
            currentCoroutineContext().ensureActive()
            if (generation == historyGeneration) {
                _activeWorkspaceId.value = workspace.id
                _workspaces.value = list
            }
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        val deletionHandler = workspaceDeletionHandler
        val generation = historyGeneration
        launchNavigation(errorPrefix = "Couldn't delete the workspace") { store ->
            if (store.listWorkspaces().size <= 1) return@launchNavigation
            val deletingActive = _activeWorkspaceId.value == workspaceId
            store.deleteWorkspace(workspaceId)
            currentCoroutineContext().ensureActive()
            if (generation != historyGeneration) return@launchNavigation
            if (deletingActive) conversationReady = false
            var cleanupError: Exception? = null
            try {
                deletionHandler(workspaceId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                cleanupError = error
            }
            // Deletion has committed. Never roll selections back into deleted storage.
            try {
                if (deletingActive) {
                    stageActiveContext(store)
                } else {
                    val list = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
                    currentCoroutineContext().ensureActive()
                    if (generation == historyGeneration) _workspaces.value = list
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == historyGeneration) {
                    reportNavigationError("Couldn't delete the workspace", error, cleanupError)
                }
                return@launchNavigation
            }
            if (generation == historyGeneration && cleanupError != null) {
                reportNavigationError("Couldn't delete the workspace", cleanupError)
            }
        }
    }

    private fun loadWorkspacesAndActiveConversation(startWithNewConversation: Boolean = false) {
        launchNavigation { store ->
            stageActiveContext(store, startWithNewConversation = startWithNewConversation)
            launchHistoryReconciliation(store, historyGeneration)
        }
    }

    private fun launchHistoryReconciliation(store: ConversationStore, generation: Long) {
        reconciliationJob?.cancel()
        reconciliationJob = viewModelScope.launch {
            try {
                store.reconcileHistory()
                // Serialize the workspace refresh with navigation so a suspended read cannot
                // overwrite a newer foreground operation's committed workspace list.
                navigationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (generation != historyGeneration) return@withLock
                    val list = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
                    currentCoroutineContext().ensureActive()
                    if (generation == historyGeneration) _workspaces.value = list
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == historyGeneration) reportNavigationError(null, error)
            }
        }
    }

    private fun captureNavigationState() = NavigationState(
        conversationId = currentConversationId,
        workspaceId = _activeWorkspaceId.value,
        messages = _messages.value.orEmpty().toList(),
        history = conversationHistory.toList(),
        nextMessageId = nextMessageId,
        workspaces = _workspaces.value.orEmpty().toList(),
        ready = conversationReady
    )

    private fun restoreNavigationState(state: NavigationState) {
        currentConversationId = state.conversationId
        _activeConversationId.value = state.conversationId
        _activeWorkspaceId.value = state.workspaceId
        conversationHistory.clear()
        conversationHistory.addAll(state.history)
        nextMessageId = state.nextMessageId
        _messages.value = state.messages
        _workspaces.value = state.workspaces
        conversationReady = state.ready
    }

    private fun reportNavigationError(prefix: String?, error: Exception, detail: Exception? = null) {
        val message = error.message ?: error.javaClass.simpleName
        _storageError.value = buildString {
            if (prefix != null) append("$prefix: ")
            append(message)
            if (detail != null) append("; ${detail.message ?: detail.javaClass.simpleName}")
        }
    }

    private fun launchNavigation(
        errorPrefix: String? = null,
        previousState: NavigationState? = null,
        beforeStart: (() -> Unit)? = null,
        block: suspend (ConversationStore) -> Unit
    ): Job? {
        val store = conversationStore ?: return null
        if (_isLoading.value == true || replacingHistory) return null
        val generation = historyGeneration
        pendingConversationOperations++
        _isConversationLoading.value = true
        beforeStart?.invoke()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            navigationMutex.withLock {
                if (generation != historyGeneration) return@withLock
                currentCoroutineContext().ensureActive()
                val previous = previousState ?: captureNavigationState()
                try {
                    block(store)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (generation != historyGeneration) return@withLock
                    var rollbackError: Exception? = null
                    try {
                        previous.workspaceId?.let { store.switchActiveWorkspace(it) }
                        previous.conversationId?.let { store.switchActiveConversation(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (rollback: Exception) {
                        rollbackError = rollback
                    }
                    currentCoroutineContext().ensureActive()
                    if (generation == historyGeneration) {
                        restoreNavigationState(previous)
                        if (rollbackError != null) conversationReady = false
                        reportNavigationError(errorPrefix, error, rollbackError)
                    }
                }
            }
        }
        trackNavigationJob(job, generation)
        job.start()
        return job
    }

    private fun trackNavigationJob(job: Job, generation: Long) {
        navigationJobs.add(job)
        job.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                navigationJobs.remove(job)
                if (generation == historyGeneration) {
                    pendingConversationOperations--
                    _isConversationLoading.value = pendingConversationOperations > 0
                }
            }
        }
    }

    private suspend fun stageActiveContext(
        store: ConversationStore,
        workspace: WorkspaceSummary? = null,
        conversation: ConversationSummary? = null,
        startWithNewConversation: Boolean = false
    ) {
        val generation = historyGeneration
        currentCoroutineContext().ensureActive()
        val activeWorkspace = workspace ?: store.getOrCreateActiveWorkspace()
        val workspaces = store.listWorkspaces().map { UiWorkspace(it.id, it.name) }
        val activeConversation = conversation ?: if (startWithNewConversation) {
            store.createConversation()
        } else {
            store.getOrCreateActiveConversation()
        }
        val loaded = prepareConversation(store, activeConversation)
        currentCoroutineContext().ensureActive()
        if (generation != historyGeneration) return
        currentConversationId = loaded.id
        _activeConversationId.value = loaded.id
        _activeWorkspaceId.value = activeWorkspace.id
        conversationHistory.clear()
        conversationHistory.addAll(loaded.history)
        nextMessageId = loaded.nextMessageId
        _messages.value = loaded.messages
        _workspaces.value = workspaces
        conversationReady = true
        _storageError.value = null
        observeConversationsForActiveWorkspace(store, activeWorkspace.id)
    }

    private suspend fun prepareConversation(
        store: ConversationStore,
        conversation: ConversationSummary
    ): LoadedConversation {
        val storedMessages = store.loadMessages(conversation.id)
        val (uiMessages, promptHistory) = withContext(messagePreparationDispatcher) {
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
            val promptHistory = storedMessages.map { stored ->
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
            uiMessages to promptHistory
        }
        return LoadedConversation(
            id = conversation.id,
            messages = uiMessages,
            history = promptHistory,
            nextMessageId = (storedMessages.maxOfOrNull { it.id } ?: 0L) + 1L
        )
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
