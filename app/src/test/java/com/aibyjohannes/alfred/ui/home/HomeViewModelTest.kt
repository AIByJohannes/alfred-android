package com.aibyjohannes.alfred.ui.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationSummary
import com.aibyjohannes.alfred.data.local.StoredChatMessage
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private val apiKeyStore: ApiKeyStore = mockk(relaxed = true)
    private val repository: ChatRepository = mockk(relaxed = true)
    private val conversationStore: ConversationStore = mockk(relaxed = true)

    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = HomeViewModel()

        val defaultWorkspace = WorkspaceSummary("1", "Personal")
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns defaultWorkspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(defaultWorkspace)
        coEvery { conversationStore.observeConversations(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `chat history load failure is retryable without replacing current state`() = runTest {
        val workspace = WorkspaceSummary("1", "Personal")
        val conversation = ConversationSummary("1", "Recovered", System.currentTimeMillis())
        coEvery { conversationStore.getOrCreateActiveWorkspace() } throws
            IllegalStateException("Provider temporarily unavailable")

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        assert(viewModel.storageError.value == "Provider temporarily unavailable")
        assert(viewModel.conversations.value.orEmpty().isEmpty())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace)
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "Still here")
        )
        coEvery { conversationStore.listConversations() } returns listOf(conversation)

        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()

        assert(viewModel.storageError.value == null)
        assert(viewModel.activeConversationId.value == "1")
        assert(viewModel.messages.value.orEmpty().single().content == "Still here")
    }

    @Test
    fun `createConversationAndSwitch creates new conversation if current is non-empty`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary("1", "First Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary("2", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        every { apiKeyStore.loadModel() } returns "google/gemini-3.1-flash-lite-preview"
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns listOf(
            StoredChatMessage(id = 1L, role = "user", content = "Hello")
        )
        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages("2") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(initialConversation, newConversation)

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createConversationAndSwitch()
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.createConversation() }
    }

    @Test
    fun `cold start creates a fresh conversation instead of restoring the previous one`() = runTest {
        val workspace = WorkspaceSummary("1", "Personal")
        val newConversation = ConversationSummary("new", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace)
        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages("new") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(newConversation)

        viewModel.initialize(
            apiKeyStore = apiKeyStore,
            repository = repository,
            conversationStore = conversationStore,
            startWithNewConversation = true
        )
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationStore.createConversation() }
        coVerify(exactly = 0) { conversationStore.getOrCreateActiveConversation() }
        assertEquals("new", viewModel.activeConversationId.value)
    }

    @Test
    fun `createConversationAndSwitch is idempotent if current is empty`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary("1", null, System.currentTimeMillis())
        val newConversation = ConversationSummary("2", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        every { apiKeyStore.loadModel() } returns "google/gemini-3.1-flash-lite-preview"
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages("2") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(initialConversation)

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createConversationAndSwitch()
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 0) { conversationStore.createConversation() }
    }

    @Test
    fun `overlapping new chat requests create only one empty conversation`() = runTest {
        val initialConversation = ConversationSummary("1", "First Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary("2", null, System.currentTimeMillis())
        val firstCreateStarted = CompletableDeferred<Unit>()
        val releaseFirstCreate = CompletableDeferred<Unit>()
        var createCalls = 0

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns listOf(
            StoredChatMessage(id = 1L, role = ChatMessage.ROLE_USER, content = "Hello")
        )
        coEvery { conversationStore.loadMessages("2") } returns emptyList()
        coEvery { conversationStore.createConversation() } coAnswers {
            createCalls++
            if (createCalls == 1) {
                firstCreateStarted.complete(Unit)
                releaseFirstCreate.await()
            }
            newConversation
        }
        coEvery { conversationStore.listConversations() } returns listOf(initialConversation, newConversation)

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createConversationAndSwitch()
        viewModel.createConversationAndSwitch()
        testScheduler.runCurrent()
        assert(firstCreateStarted.isCompleted)
        releaseFirstCreate.complete(Unit)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationStore.createConversation() }
    }

    @Test
    fun `new chat is ignored while a response is streaming`() = runTest {
        val conversation = ConversationSummary("1", "Current", System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flow

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Keep this response in the current chat")
        viewModel.createConversationAndSwitch()
        testScheduler.runCurrent()

        coVerify(exactly = 0) { conversationStore.createConversation() }
    }

    @Test
    fun `deleteConversation deletes active conversation and switches to remaining one`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary("1", "First Chat", System.currentTimeMillis())
        val remainingConversation = ConversationSummary("2", "Second Chat", System.currentTimeMillis() + 1000)

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.loadMessages("2") } returns emptyList()
        coEvery { conversationStore.listConversations() } returnsMany listOf(
            listOf(initialConversation, remainingConversation),
            listOf(remainingConversation)
        )
        coEvery { conversationStore.switchActiveConversation("2") } returns remainingConversation

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteConversation("1")
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.deleteConversation("1") }
        coVerify(exactly = 1) { conversationStore.switchActiveConversation("2") }
    }

    @Test
    fun `selectConversation reloads active conversation when selected id is invalid`() = runTest {
        // Arrange
        val activeConversation = ConversationSummary("1", "Active Chat", System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)
        coEvery { conversationStore.switchActiveConversation("99") } throws IllegalArgumentException("Invalid")

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.selectConversation("99")
        testScheduler.advanceUntilIdle()

        // Assert
        assert(viewModel.activeConversationId.value == "1")
        coVerify(exactly = 1) { conversationStore.switchActiveConversation("99") }
        coVerify(atLeast = 2) { conversationStore.getOrCreateActiveConversation() }
    }

    @Test
    fun `selectConversation exposes loading state until file-backed switch completes`() = runTest {
        val initialConversation = ConversationSummary("1", "Current", System.currentTimeMillis())
        val selectedConversation = ConversationSummary("2", "Selected", System.currentTimeMillis())
        val switchStarted = CompletableDeferred<Unit>()
        val finishSwitch = CompletableDeferred<Unit>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(any()) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(initialConversation, selectedConversation)
        coEvery { conversationStore.switchActiveConversation("2") } coAnswers {
            switchStarted.complete(Unit)
            finishSwitch.await()
            selectedConversation
        }

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.selectConversation("2")
        testScheduler.runCurrent()

        assertTrue(switchStarted.isCompleted)
        assertEquals(true, viewModel.isConversationLoading.value)

        finishSwitch.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.isConversationLoading.value)
        assertEquals("2", viewModel.activeConversationId.value)
    }

    @Test
    fun `switchWorkspace updates active workspace and loads its active conversation`() = runTest {
        // Arrange
        val workspace1 = WorkspaceSummary("1", "Personal")
        val workspace2 = WorkspaceSummary("2", "Work")
        val conversationInW2 = ConversationSummary("3", "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace1
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace1, workspace2)
        coEvery { conversationStore.switchActiveWorkspace("2") } returns workspace2
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversationInW2
        coEvery { conversationStore.loadMessages("3") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversationInW2)
        coEvery { conversationStore.observeConversations("2") } returns flowOf(listOf(conversationInW2))

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.switchWorkspace("2")
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.switchActiveWorkspace("2") }
        assert(viewModel.activeWorkspaceId.value == "2")
        assert(viewModel.activeConversationId.value == "3")
        assert(viewModel.conversations.value?.firstOrNull()?.id == "3")
    }

    @Test
    fun `created workspace is not exposed until its conversation is loaded`() = runTest {
        val personal = WorkspaceSummary("1", "Personal")
        val work = WorkspaceSummary("2", "Work")
        val personalConversation = ConversationSummary("1", "Personal Chat", System.currentTimeMillis())
        val workConversation = ConversationSummary("2", null, System.currentTimeMillis())
        val workConversationLoadStarted = CompletableDeferred<Unit>()
        val releaseWorkConversation = CompletableDeferred<Unit>()
        var activeConversationCalls = 0

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns personal
        coEvery { conversationStore.listWorkspaces() } returns listOf(personal, work)
        coEvery { conversationStore.createWorkspace("Work") } returns work
        coEvery { conversationStore.getOrCreateActiveConversation() } coAnswers {
            activeConversationCalls++
            if (activeConversationCalls == 1) {
                personalConversation
            } else {
                workConversationLoadStarted.complete(Unit)
                releaseWorkConversation.await()
                workConversation
            }
        }
        coEvery { conversationStore.loadMessages(any()) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns emptyList()

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createWorkspace("Work")
        testScheduler.runCurrent()

        assertTrue(workConversationLoadStarted.isCompleted)
        assertEquals("1", viewModel.activeWorkspaceId.value)
        assertEquals("1", viewModel.activeConversationId.value)

        releaseWorkConversation.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("2", viewModel.activeWorkspaceId.value)
        assertEquals("2", viewModel.activeConversationId.value)
    }

    @Test
    fun `deleteWorkspace cascades and switches active workspace to remaining one`() = runTest {
        // Arrange
        val workspace1 = WorkspaceSummary("1", "Personal")
        val workspace2 = WorkspaceSummary("2", "Work")
        val conversationInW2 = ConversationSummary("3", "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returnsMany listOf(workspace1, workspace2, workspace2)
        coEvery { conversationStore.listWorkspaces() } returnsMany listOf(
            listOf(workspace1, workspace2), // 1. initialize
            listOf(workspace1, workspace2), // 2. pre-delete check
            listOf(workspace2)              // 3. post-delete refresh
        )
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversationInW2
        coEvery { conversationStore.loadMessages("3") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversationInW2)

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace("1")
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.deleteWorkspace("1") }
        coVerify(atLeast = 1) { conversationStore.getOrCreateActiveWorkspace() }
        assert(viewModel.activeWorkspaceId.value == "2")
        assert(viewModel.activeConversationId.value == "3")
    }

    @Test
    fun `sendMessage persists completed structured stream atomically`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.TextDelta(0, "Hello"),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Hello",
                    toolCalls = emptyList(),
                    intermediateMessages = listOf(
                        CoreChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "Hello")
                    )
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Hi")
        testScheduler.advanceUntilIdle()

        assert(viewModel.messages.value?.lastOrNull()?.content == "Hello")
        coVerify(exactly = 1) { conversationStore.appendMessages(eq("1"), match { it.size == 2 }) }
        coVerify(exactly = 0) { conversationStore.appendMessage(any(), any(), any()) }
    }

    @Test
    fun `sendMessage renders compact reasoning and tool traces`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reason-1",
                textChunk = null,
                summaryChunk = "Need current data."
            ),
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = "call-1",
                name = "WebSearchTool",
                argumentsChunk = """{"query":"Kotlin release"}"""
            ),
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = """{"query":"Kotlin release"}"""
            ),
            ChatStreamEvent.ToolResultAvailable(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                resultPreview = "Kotlin 2.2 is available.",
                isError = false
            ),
            ChatStreamEvent.PassStarted(1),
            ChatStreamEvent.TextDelta(1, "Kotlin 2.2 is available."),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Kotlin 2.2 is available.",
                    toolCalls = emptyList(),
                    intermediateMessages = listOf(
                        CoreChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "Need current data.",
                            kind = CoreChatMessageKind.REASONING,
                            reasoningSummary = "Need current data.",
                            searchable = false
                        ),
                        CoreChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = """{"query":"Kotlin release"}""",
                            kind = CoreChatMessageKind.TOOL_CALL,
                            toolCallId = "call-1",
                            toolName = "WebSearchTool",
                            toolArgumentsJson = """{"query":"Kotlin release"}""",
                            searchable = false
                        ),
                        CoreChatMessage(
                            role = ChatMessage.ROLE_TOOL,
                            content = "Kotlin 2.2 is available.",
                            kind = CoreChatMessageKind.TOOL_RESULT,
                            toolCallId = "call-1",
                            toolName = "WebSearchTool",
                            searchable = false
                        ),
                        CoreChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "Kotlin 2.2 is available."
                        )
                    )
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("What is the latest Kotlin release?")
        testScheduler.advanceUntilIdle()

        val assistant = viewModel.messages.value.orEmpty().last()
        assert(assistant.content == "Kotlin 2.2 is available.")
        assert(assistant.traceItems.any { it.kind == UiTraceKind.REASONING && it.content.contains("Need current data") })
        assert(assistant.traceItems.any { it.kind == UiTraceKind.TOOL_CALL && it.content.contains("Kotlin release") })
        assert(assistant.traceItems.any { it.kind == UiTraceKind.TOOL_RESULT && it.content.contains("Kotlin 2.2") })
        coVerify(exactly = 1) {
            conversationStore.appendMessages(
                eq("1"),
                match { drafts ->
                    drafts.any { it.kind == ChatMessage.KIND_TOOL_CALL && it.searchable == false } &&
                        drafts.any { it.kind == ChatMessage.KIND_TOOL_RESULT && it.searchable == false }
                }
            )
        }
    }

    @Test
    fun `sendMessage moves pre tool draft into trace before final answer`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.TextDelta(0, "I should check first."),
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = """{"query":"weather"}"""
            ),
            ChatStreamEvent.ToolResultAvailable(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                resultPreview = "Sunny",
                isError = false
            ),
            ChatStreamEvent.PassStarted(1),
            ChatStreamEvent.TextDelta(1, "It is sunny."),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "It is sunny.",
                    toolCalls = emptyList(),
                    intermediateMessages = listOf(
                        CoreChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "I should check first.",
                            searchable = false
                        ),
                        CoreChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "It is sunny."
                        )
                    )
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Weather?")
        testScheduler.advanceUntilIdle()

        val assistant = viewModel.messages.value.orEmpty().last()
        assert(assistant.content == "It is sunny.")
        assert(assistant.traceItems.any { it.kind == UiTraceKind.ASSISTANT_DRAFT && it.content == "I should check first." })
    }

    @Test
    fun `sendMessage groups reasoning chunks into a single trace item even if they have different ids`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "chunk-1",
                textChunk = null,
                summaryChunk = "Thinking "
            ),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "chunk-2",
                textChunk = null,
                summaryChunk = "about "
            ),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "chunk-3",
                textChunk = null,
                summaryChunk = "this."
            ),
            ChatStreamEvent.TextDelta(0, "Done."),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Done.",
                    toolCalls = emptyList(),
                    intermediateMessages = emptyList()
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Test reasoning grouping")
        testScheduler.advanceUntilIdle()

        val assistant = viewModel.messages.value.orEmpty().last()
        val reasoningTraces = assistant.traceItems.filter { it.kind == UiTraceKind.REASONING }
        
        assert(reasoningTraces.size == 1) { "Expected exactly 1 reasoning trace, but found ${reasoningTraces.size}" }
        assert(reasoningTraces.first().content == "Thinking about this.") { "Expected combined reasoning content, but was ${reasoningTraces.first().content}" }
    }

    @Test
    fun `sendMessage preserves accumulated reasoning when completion contains only final chunk`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = "Thinking ",
                summaryChunk = null
            ),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = "about ",
                summaryChunk = null
            ),
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = "this.",
                summaryChunk = null
            ),
            ChatStreamEvent.ReasoningComplete(
                passIndex = 0,
                id = "reasoning-0",
                content = listOf("this."),
                summary = emptyList(),
                encrypted = null
            ),
            ChatStreamEvent.TextDelta(0, "Done."),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Done.",
                    toolCalls = emptyList(),
                    intermediateMessages = emptyList()
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Test DeepSeek reasoning completion")
        testScheduler.advanceUntilIdle()

        val assistant = viewModel.messages.value.orEmpty().last()
        val reasoningTrace = assistant.traceItems.single { it.kind == UiTraceKind.REASONING }

        assert(reasoningTrace.content == "Thinking about this.") {
            "Expected accumulated reasoning content, but was ${reasoningTrace.content}"
        }
    }

    @Test
    fun `sendMessage groups tool call chunks into a single trace item even if subsequent chunks have null ids`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flowOf(
            ChatStreamEvent.PassStarted(0),
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = "call-1",
                name = "WebSearchTool",
                argumentsChunk = "{\"qu"
            ),
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = null,
                name = null,
                argumentsChunk = "ery\":\"Kot"
            ),
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = null,
                name = null,
                argumentsChunk = "lin\"}"
            ),
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = "{\"query\":\"Kotlin\"}"
            ),
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Done.",
                    toolCalls = emptyList(),
                    intermediateMessages = emptyList()
                )
            )
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Test tool grouping")
        testScheduler.advanceUntilIdle()

        val assistant = viewModel.messages.value.orEmpty().last()
        val toolTraces = assistant.traceItems.filter { it.kind == UiTraceKind.TOOL_CALL }

        assert(toolTraces.size == 1) { "Expected exactly 1 tool trace, but found ${toolTraces.size}" }
        assert(toolTraces.first().content == "{\"query\":\"Kotlin\"}") { "Expected combined tool args, but was ${toolTraces.first().content}" }
    }

    @Test
    fun `sendMessage updates messages list incrementally during streaming`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flow

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Checking weather")
        testScheduler.advanceTimeBy(1)

        // 1. Emit PassStarted
        flow.emit(ChatStreamEvent.PassStarted(0))
        testScheduler.advanceUntilIdle()
        var assistant = viewModel.messages.value.orEmpty().last()
        assert(assistant.content.isEmpty())
        assert(assistant.traceItems.isEmpty())

        // 2. Emit ReasoningDelta
        flow.emit(
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = "Thinking",
                summaryChunk = null
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        var reasoningTrace = assistant.traceItems.firstOrNull { it.kind == UiTraceKind.REASONING }
        assert(reasoningTrace != null)
        assert(reasoningTrace?.content == "Thinking")

        // 3. Emit ReasoningDelta append
        flow.emit(
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = " more",
                summaryChunk = null
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        reasoningTrace = assistant.traceItems.firstOrNull { it.kind == UiTraceKind.REASONING }
        assert(reasoningTrace?.content == "Thinking more")

        // 4. Emit ToolCallDelta
        flow.emit(
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = "call-1",
                name = "WebSearchTool",
                argumentsChunk = "{\"qu"
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        var toolTrace = assistant.traceItems.firstOrNull { it.kind == UiTraceKind.TOOL_CALL }
        assert(toolTrace != null)
        assert(toolTrace?.content == "{\"qu")

        // 5. Emit ToolCallDelta append
        flow.emit(
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = "call-1",
                name = null,
                argumentsChunk = "ery\"}"
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        toolTrace = assistant.traceItems.firstOrNull { it.kind == UiTraceKind.TOOL_CALL }
        assert(toolTrace?.content == "{\"query\"}")

        // 6. Emit ToolCallRequested
        flow.emit(
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = "{\"query\"}"
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        toolTrace = assistant.traceItems.firstOrNull { it.kind == UiTraceKind.TOOL_CALL }
        assert(toolTrace?.content == "{\"query\"}")

        // 7. Emit Completed
        flow.emit(
            ChatStreamEvent.Completed(
                ChatTurnResult(
                    content = "Weather is nice.",
                    toolCalls = emptyList(),
                    intermediateMessages = emptyList()
                )
            )
        )
        testScheduler.advanceUntilIdle()
        assistant = viewModel.messages.value.orEmpty().last()
        assert(assistant.content == "Weather is nice.")
    }

    @Test
    fun `sendMessage keeps the CPU awake until the stream completes`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())
        val chatRunPowerKeeper = object : ChatRunPowerKeeper {
            var isHeld = false
            var acquireCalls = 0
            var releaseCalls = 0

            override fun acquire() {
                isHeld = true
                acquireCalls++
            }

            override fun release() {
                isHeld = false
                releaseCalls++
            }
        }

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            assertTrue(chatRunPowerKeeper.isHeld)
            emit(
                ChatStreamEvent.Completed(
                    ChatTurnResult(
                        content = "Done.",
                        toolCalls = emptyList(),
                        intermediateMessages = emptyList()
                    )
                )
            )
        }

        viewModel.initialize(
            apiKeyStore = apiKeyStore,
            repository = repository,
            conversationStore = conversationStore,
            chatRunPowerKeeper = chatRunPowerKeeper
        )
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Keep working while the screen is off")
        testScheduler.advanceUntilIdle()

        assertEquals(1, chatRunPowerKeeper.acquireCalls)
        assertEquals(1, chatRunPowerKeeper.releaseCalls)
        assertTrue(!chatRunPowerKeeper.isHeld)
    }

    @Test
    fun `sendMessage releases the CPU wake lock when streaming fails`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())
        val chatRunPowerKeeper = object : ChatRunPowerKeeper {
            var isHeld = false
            var releaseCalls = 0

            override fun acquire() {
                isHeld = true
            }

            override fun release() {
                isHeld = false
                releaseCalls++
            }
        }

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            assertTrue(chatRunPowerKeeper.isHeld)
            throw IllegalStateException("Network interrupted")
        }

        viewModel.initialize(
            apiKeyStore = apiKeyStore,
            repository = repository,
            conversationStore = conversationStore,
            chatRunPowerKeeper = chatRunPowerKeeper
        )
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Do not leak a wake lock")
        testScheduler.advanceUntilIdle()

        assertEquals(1, chatRunPowerKeeper.releaseCalls)
        assertTrue(!chatRunPowerKeeper.isHeld)
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `sendMessage preserves user message content during reasoning and tool call streaming`() = runTest {
        val conversation = ConversationSummary("1", null, System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any()) } returns flow

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        val inputMessageText = "Checking weather online"
        viewModel.sendMessage(inputMessageText)
        testScheduler.advanceTimeBy(1)

        // 1. Emit PassStarted
        flow.emit(ChatStreamEvent.PassStarted(0))
        testScheduler.advanceUntilIdle()
        var messages = viewModel.messages.value.orEmpty()
        assert(messages.size >= 1)
        assert(messages[0].isUser)
        assert(messages[0].content == inputMessageText)

        // 2. Emit ReasoningDelta
        flow.emit(
            ChatStreamEvent.ReasoningDelta(
                passIndex = 0,
                id = "reasoning-0",
                textChunk = "Thinking",
                summaryChunk = null
            )
        )
        testScheduler.advanceUntilIdle()
        messages = viewModel.messages.value.orEmpty()
        assert(messages[0].content == inputMessageText) { "User message was blanked out after ReasoningDelta" }

        // 3. Emit ToolCallDelta
        flow.emit(
            ChatStreamEvent.ToolCallDelta(
                passIndex = 0,
                id = "call-1",
                name = "WebSearchTool",
                argumentsChunk = "{\"qu"
            )
        )
        testScheduler.advanceUntilIdle()
        messages = viewModel.messages.value.orEmpty()
        assert(messages[0].content == inputMessageText) { "User message was blanked out after ToolCallDelta" }

        // 4. Emit ToolCallRequested
        flow.emit(
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = "{\"query\":\"Kotlin\"}"
            )
        )
        testScheduler.advanceUntilIdle()
        messages = viewModel.messages.value.orEmpty()
        assert(messages[0].content == inputMessageText) { "User message was blanked out after ToolCallRequested" }
    }

    @Test
    fun `createWorkspace creates new workspace and switches to it`() = runTest {
        val newWorkspace = WorkspaceSummary("2", "Work")
        val activeConversation = ConversationSummary("3", null, System.currentTimeMillis())

        coEvery { conversationStore.createWorkspace("Work") } returns newWorkspace
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.listWorkspaces() } returns listOf(WorkspaceSummary("1", "Personal"), newWorkspace)
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createWorkspace("Work")
        testScheduler.advanceUntilIdle()

        assertEquals("2", viewModel.activeWorkspaceId.value)
        assertEquals("3", viewModel.activeConversationId.value)
        coVerify { conversationStore.createWorkspace("Work") }
    }

    @Test
    fun `switchWorkspace switches active workspace and loads active conversation`() = runTest {
        val workWorkspace = WorkspaceSummary("2", "Work")
        val activeConversation = ConversationSummary("4", "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.switchActiveWorkspace("2") } returns workWorkspace
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.switchWorkspace("2")
        testScheduler.advanceUntilIdle()

        assertEquals("2", viewModel.activeWorkspaceId.value)
        assertEquals("4", viewModel.activeConversationId.value)
        coVerify { conversationStore.switchActiveWorkspace("2") }
    }

    @Test
    fun `renameWorkspace renames workspace and keeps active workspace id`() = runTest {
        val activeWs = WorkspaceSummary("1", "Personal Renamed")
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns activeWs

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.renameWorkspace("1", "Personal Renamed")
        testScheduler.advanceUntilIdle()

        coVerify { conversationStore.renameWorkspace("1", "Personal Renamed") }
        assertEquals("1", viewModel.activeWorkspaceId.value)
    }

    @Test
    fun `deleteWorkspace deletes workspace only when multiple workspaces exist`() = runTest {
        val ws1 = WorkspaceSummary("1", "Personal")
        val ws2 = WorkspaceSummary("2", "Work")

        // Case 1: Only 1 workspace exists -> delete does nothing
        coEvery { conversationStore.listWorkspaces() } returns listOf(ws1)
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace("1")
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { conversationStore.deleteWorkspace(any()) }

        // Case 2: Multiple workspaces exist -> delete executes
        coEvery { conversationStore.listWorkspaces() } returns listOf(ws1, ws2)
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns ws2
        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace("1")
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { conversationStore.deleteWorkspace("1") }
        assertEquals("2", viewModel.activeWorkspaceId.value)
    }

    @Test
    fun `clearChat creates new empty conversation without deleting active one`() = runTest {
        val activeConversation = ConversationSummary("1", "Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary("2", null, System.currentTimeMillis())
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.loadMessages("1") } returns listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "Hello"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "Hi")
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.messages.value.orEmpty().size)

        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages("2") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation, newConversation)

        viewModel.clearChat()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { conversationStore.deleteConversation(any()) }
        coVerify(exactly = 1) { conversationStore.createConversation() }
        assertTrue(viewModel.messages.value.orEmpty().isEmpty())
    }

    @Test
    fun `sharedText live data updates on setSharedText and clears on consumeSharedText`() = runTest {
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        assert(viewModel.sharedText.value == null)

        viewModel.setSharedText("Shared content example")
        assertEquals("Shared content example", viewModel.sharedText.value)

        viewModel.consumeSharedText()
        assert(viewModel.sharedText.value == null)
    }
}
