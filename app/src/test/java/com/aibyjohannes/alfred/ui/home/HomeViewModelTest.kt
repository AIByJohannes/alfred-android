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

        val defaultWorkspace = WorkspaceSummary(1L, "Personal")
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns defaultWorkspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(defaultWorkspace)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `chat history load failure is retryable without replacing current state`() = runTest {
        val workspace = WorkspaceSummary(1L, "Personal")
        val conversation = ConversationSummary(1L, "Recovered", System.currentTimeMillis())
        coEvery { conversationStore.getOrCreateActiveWorkspace() } throws
            IllegalStateException("Provider temporarily unavailable")

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        assert(viewModel.storageError.value == "Provider temporarily unavailable")
        assert(viewModel.conversations.value.orEmpty().isEmpty())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace)
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "Still here")
        )
        coEvery { conversationStore.listConversations() } returns listOf(conversation)

        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()

        assert(viewModel.storageError.value == null)
        assert(viewModel.activeConversationId.value == 1L)
        assert(viewModel.messages.value.orEmpty().single().content == "Still here")
    }

    @Test
    fun `createConversationAndSwitch creates new conversation if current is non-empty`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary(1L, "First Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary(2L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        every { apiKeyStore.loadModel() } returns "google/gemini-3.1-flash-lite-preview"
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(1L) } returns listOf(
            StoredChatMessage(id = 1L, role = "user", content = "Hello")
        )
        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages(2L) } returns emptyList()
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
    fun `createConversationAndSwitch is idempotent if current is empty`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        every { apiKeyStore.loadModel() } returns "google/gemini-3.1-flash-lite-preview"
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
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
        val initialConversation = ConversationSummary(1L, "First Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary(2L, null, System.currentTimeMillis())
        val firstCreateStarted = CompletableDeferred<Unit>()
        val releaseFirstCreate = CompletableDeferred<Unit>()
        var createCalls = 0

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(1L) } returns listOf(
            StoredChatMessage(id = 1L, role = ChatMessage.ROLE_USER, content = "Hello")
        )
        coEvery { conversationStore.loadMessages(2L) } returns emptyList()
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
        val conversation = ConversationSummary(1L, "Current", System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flow

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
        val initialConversation = ConversationSummary(1L, "First Chat", System.currentTimeMillis())
        val remainingConversation = ConversationSummary(2L, "Second Chat", System.currentTimeMillis() + 1000)

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.loadMessages(2L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returnsMany listOf(
            listOf(initialConversation, remainingConversation),
            listOf(remainingConversation)
        )
        coEvery { conversationStore.switchActiveConversation(2L) } returns remainingConversation

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteConversation(1L)
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.deleteConversation(1L) }
        coVerify(exactly = 1) { conversationStore.switchActiveConversation(2L) }
    }

    @Test
    fun `selectConversation reloads active conversation when selected id is invalid`() = runTest {
        // Arrange
        val activeConversation = ConversationSummary(1L, "Active Chat", System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)
        coEvery { conversationStore.switchActiveConversation(99L) } throws IllegalArgumentException("Invalid")

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.selectConversation(99L)
        testScheduler.advanceUntilIdle()

        // Assert
        assert(viewModel.activeConversationId.value == 1L)
        coVerify(exactly = 1) { conversationStore.switchActiveConversation(99L) }
        coVerify(atLeast = 2) { conversationStore.getOrCreateActiveConversation() }
    }

    @Test
    fun `switchWorkspace updates active workspace and loads its active conversation`() = runTest {
        // Arrange
        val workspace1 = WorkspaceSummary(1L, "Personal")
        val workspace2 = WorkspaceSummary(2L, "Work")
        val conversationInW2 = ConversationSummary(3L, "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace1
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace1, workspace2)
        coEvery { conversationStore.switchActiveWorkspace(2L) } returns workspace2
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversationInW2
        coEvery { conversationStore.loadMessages(3L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversationInW2)

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.switchWorkspace(2L)
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.switchActiveWorkspace(2L) }
        assert(viewModel.activeWorkspaceId.value == 2L)
        assert(viewModel.activeConversationId.value == 3L)
        assert(viewModel.conversations.value?.firstOrNull()?.id == 3L)
    }

    @Test
    fun `created workspace is not exposed until its conversation is loaded`() = runTest {
        val personal = WorkspaceSummary(1L, "Personal")
        val work = WorkspaceSummary(2L, "Work")
        val personalConversation = ConversationSummary(1L, "Personal Chat", System.currentTimeMillis())
        val workConversation = ConversationSummary(2L, null, System.currentTimeMillis())
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
        assertEquals(1L, viewModel.activeWorkspaceId.value)
        assertEquals(1L, viewModel.activeConversationId.value)

        releaseWorkConversation.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.activeWorkspaceId.value)
        assertEquals(2L, viewModel.activeConversationId.value)
    }

    @Test
    fun `deleteWorkspace cascades and switches active workspace to remaining one`() = runTest {
        // Arrange
        val workspace1 = WorkspaceSummary(1L, "Personal")
        val workspace2 = WorkspaceSummary(2L, "Work")
        val conversationInW2 = ConversationSummary(3L, "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returnsMany listOf(workspace1, workspace2, workspace2)
        coEvery { conversationStore.listWorkspaces() } returnsMany listOf(
            listOf(workspace1, workspace2), // 1. initialize
            listOf(workspace1, workspace2), // 2. pre-delete check
            listOf(workspace2)              // 3. post-delete refresh
        )
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversationInW2
        coEvery { conversationStore.loadMessages(3L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversationInW2)

        // Act
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace(1L)
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.deleteWorkspace(1L) }
        coVerify(atLeast = 1) { conversationStore.getOrCreateActiveWorkspace() }
        assert(viewModel.activeWorkspaceId.value == 2L)
        assert(viewModel.activeConversationId.value == 3L)
    }

    @Test
    fun `sendMessage persists completed structured stream atomically`() = runTest {
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
        coVerify(exactly = 1) { conversationStore.appendMessages(eq(1L), match { it.size == 2 }) }
        coVerify(exactly = 0) { conversationStore.appendMessage(any(), any(), any()) }
    }

    @Test
    fun `sendMessage renders compact reasoning and tool traces`() = runTest {
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
                eq(1L),
                match { drafts ->
                    drafts.any { it.kind == ChatMessage.KIND_TOOL_CALL && it.searchable == false } &&
                        drafts.any { it.kind == ChatMessage.KIND_TOOL_RESULT && it.searchable == false }
                }
            )
        }
    }

    @Test
    fun `sendMessage moves pre tool draft into trace before final answer`() = runTest {
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flowOf(
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
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flow

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
    fun `sendMessage preserves user message content during reasoning and tool call streaming`() = runTest {
        val conversation = ConversationSummary(1L, null, System.currentTimeMillis())
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<ChatStreamEvent>()

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages(1L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any()) } returns flow

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
        val newWorkspace = WorkspaceSummary(2L, "Work")
        val activeConversation = ConversationSummary(3L, null, System.currentTimeMillis())

        coEvery { conversationStore.createWorkspace("Work") } returns newWorkspace
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.listWorkspaces() } returns listOf(WorkspaceSummary(1L, "Personal"), newWorkspace)
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.createWorkspace("Work")
        testScheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.activeWorkspaceId.value)
        assertEquals(3L, viewModel.activeConversationId.value)
        coVerify { conversationStore.createWorkspace("Work") }
    }

    @Test
    fun `switchWorkspace switches active workspace and loads active conversation`() = runTest {
        val workWorkspace = WorkspaceSummary(2L, "Work")
        val activeConversation = ConversationSummary(4L, "Work Chat", System.currentTimeMillis())

        coEvery { conversationStore.switchActiveWorkspace(2L) } returns workWorkspace
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.listConversations() } returns listOf(activeConversation)

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.switchWorkspace(2L)
        testScheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.activeWorkspaceId.value)
        assertEquals(4L, viewModel.activeConversationId.value)
        coVerify { conversationStore.switchActiveWorkspace(2L) }
    }

    @Test
    fun `renameWorkspace renames workspace and keeps active workspace id`() = runTest {
        val activeWs = WorkspaceSummary(1L, "Personal Renamed")
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns activeWs

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.renameWorkspace(1L, "Personal Renamed")
        testScheduler.advanceUntilIdle()

        coVerify { conversationStore.renameWorkspace(1L, "Personal Renamed") }
        assertEquals(1L, viewModel.activeWorkspaceId.value)
    }

    @Test
    fun `deleteWorkspace deletes workspace only when multiple workspaces exist`() = runTest {
        val ws1 = WorkspaceSummary(1L, "Personal")
        val ws2 = WorkspaceSummary(2L, "Work")

        // Case 1: Only 1 workspace exists -> delete does nothing
        coEvery { conversationStore.listWorkspaces() } returns listOf(ws1)
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace(1L)
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { conversationStore.deleteWorkspace(any()) }

        // Case 2: Multiple workspaces exist -> delete executes
        coEvery { conversationStore.listWorkspaces() } returns listOf(ws1, ws2)
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns ws2
        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace(1L)
        testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { conversationStore.deleteWorkspace(1L) }
        assertEquals(2L, viewModel.activeWorkspaceId.value)
    }

    @Test
    fun `clearChat deletes active conversation and creates new empty conversation`() = runTest {
        val activeConversation = ConversationSummary(1L, "Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary(2L, null, System.currentTimeMillis())
        coEvery { conversationStore.getOrCreateActiveConversation() } returns activeConversation
        coEvery { conversationStore.loadMessages(1L) } returns listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "Hello"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "Hi")
        )

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.messages.value.orEmpty().size)

        coEvery { conversationStore.deleteConversation(1L) } just Runs
        coEvery { conversationStore.createConversation() } returns newConversation
        coEvery { conversationStore.loadMessages(2L) } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(newConversation)

        viewModel.clearChat()
        testScheduler.advanceUntilIdle()

        coVerify { conversationStore.deleteConversation(1L) }
        coVerify { conversationStore.createConversation() }
        assertTrue(viewModel.messages.value.orEmpty().isEmpty())
    }
}
