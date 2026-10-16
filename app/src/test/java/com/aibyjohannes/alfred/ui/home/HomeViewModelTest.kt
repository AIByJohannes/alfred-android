package com.aibyjohannes.alfred.ui.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.aibyjohannes.alfred.core.engine.AskUserAnswer
import com.aibyjohannes.alfred.core.engine.AskUserHandler
import com.aibyjohannes.alfred.core.engine.AskUserRequest
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
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertFalse
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
        viewModel = HomeViewModel(testDispatcher)

        every { conversationStore.storageIdentity } returns null
        val defaultWorkspace = WorkspaceSummary("1", "Personal")
        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns defaultWorkspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(defaultWorkspace)
        coEvery { conversationStore.observeConversations(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedRemoteNavigation() {
        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns ConversationSummary("first", "First", 1L)
        coEvery { conversationStore.loadMessages("first") } returns listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "First chat")
        )
        coEvery { conversationStore.switchActiveConversation("first") } returns ConversationSummary("first", "First", 1L)
        viewModel.initialize(apiKeyStore, repository, conversationStore)
    }

    @Test
    fun `failed navigation retains messages and restores the previous selection`() = runTest {
        seedRemoteNavigation()
        testScheduler.advanceUntilIdle()
        coEvery { conversationStore.switchActiveConversation("broken") } returns ConversationSummary("broken", "Broken", 2L)
        coEvery { conversationStore.loadMessages("broken") } throws IllegalStateException("Cannot read chat")

        viewModel.selectConversation("broken")
        testScheduler.advanceUntilIdle()

        assertEquals("first", viewModel.activeConversationId.value)
        assertEquals("First chat", viewModel.messages.value!!.single().content)
        assertEquals("Cannot read chat", viewModel.storageError.value)
        assertEquals(false, viewModel.isConversationLoading.value)
        coVerify { conversationStore.switchActiveConversation("first") }
    }

    @Test
    fun `New Chat keeps old identity and blocks sending until the new chat is ready`() = runTest {
        seedRemoteNavigation()
        testScheduler.advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        coEvery { conversationStore.createConversation() } coAnswers {
            release.await()
            ConversationSummary("new", null, 2L)
        }
        coEvery { conversationStore.loadMessages("new") } returns emptyList()

        viewModel.requestNewChat()
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        assertEquals("first", viewModel.activeConversationId.value)
        viewModel.sendMessage("Must not go to the old chat")
        coVerify(exactly = 0) { conversationStore.appendMessage(any(), any(), any()) }
        verify(exactly = 0) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("new", viewModel.activeConversationId.value)
        assertTrue(viewModel.messages.value!!.isEmpty())
        assertEquals(false, viewModel.isConversationLoading.value)
    }

    @Test
    fun `failed New Chat preserves current messages and clears the navigation guard`() = runTest {
        seedRemoteNavigation()
        testScheduler.advanceUntilIdle()
        coEvery { conversationStore.createConversation() } throws IllegalStateException("disk unavailable")

        viewModel.requestNewChat()
        testScheduler.advanceUntilIdle()

        assertEquals("first", viewModel.activeConversationId.value)
        assertEquals("First chat", viewModel.messages.value!!.single().content)
        assertEquals(false, viewModel.isConversationLoading.value)
        assertEquals("Couldn't create a new chat: disk unavailable", viewModel.storageError.value)
        coVerify { conversationStore.switchActiveConversation("first") }
    }

    @Test
    fun `overlapping switches are serialized and publish matching messages`() = runTest {
        seedRemoteNavigation()
        testScheduler.advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        coEvery { conversationStore.switchActiveConversation("second") } returns ConversationSummary("second", "Second", 2L)
        coEvery { conversationStore.switchActiveConversation("third") } returns ConversationSummary("third", "Third", 3L)
        coEvery { conversationStore.loadMessages("second") } coAnswers {
            release.await()
            listOf(StoredChatMessage(2L, ChatMessage.ROLE_USER, "Second chat"))
        }
        coEvery { conversationStore.loadMessages("third") } returns listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "Third chat"))

        viewModel.selectConversation("second")
        viewModel.selectConversation("third")
        testScheduler.runCurrent()
        assertEquals("first", viewModel.activeConversationId.value)
        coVerify(exactly = 0) { conversationStore.loadMessages("third") }
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("third", viewModel.activeConversationId.value)
        assertEquals("Third chat", viewModel.messages.value!!.single().content)
        assertEquals(false, viewModel.isConversationLoading.value)
    }

    @Test
    fun `background reconciliation does not keep foreground input busy`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { conversationStore.reconcileHistory() } coAnswers { release.await() }
        seedRemoteNavigation()
        testScheduler.runCurrent()

        assertEquals("first", viewModel.activeConversationId.value)
        assertEquals(false, viewModel.isConversationLoading.value)
        assertEquals("First chat", viewModel.messages.value!!.single().content)
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `replacement joins canceled reconciliation before adopting the new root`() = runTest {
        seedNavigation()
        val reconciliationStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        coEvery { conversationStore.reconcileHistory() } coAnswers {
            reconciliationStarted.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    throw IllegalStateException("obsolete reconciliation failure")
                }
            }
        }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        assertTrue(reconciliationStarted.isCompleted)
        assertFalse(viewModel.isConversationLoading.value == true)

        val replacement: ConversationStore = mockk(relaxed = true)
        seedNavigation(replacement, "replacement", "replacement-ws")
        viewModel.initialize(apiKeyStore, repository, replacement)
        testScheduler.runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertEquals(true, viewModel.isConversationLoading.value)
        assertEquals(null, viewModel.activeConversationId.value)
        coVerify(exactly = 0) { replacement.getOrCreateActiveWorkspace() }
        assertBlockedInput()

        releaseCleanup.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("replacement", viewModel.activeConversationId.value)
        assertEquals("replacement-ws", viewModel.activeWorkspaceId.value)
        assertEquals(null, viewModel.storageError.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        coVerify { replacement.reconcileHistory() }
    }

    @Test
    fun `message preparation runs on its dispatcher and commits only after completion`() = runTest {
        val preparationScheduler = TestCoroutineScheduler()
        viewModel = HomeViewModel(StandardTestDispatcher(preparationScheduler))
        seedNavigation(history = listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "prompt"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "thinking",
                kind = ChatMessage.KIND_REASONING, turnId = "turn",
                reasoningText = "private reasoning", includeInPrompt = false, searchable = false),
            StoredChatMessage(3L, ChatMessage.ROLE_ASSISTANT, "answer", turnId = "turn")
        ))
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        assertEquals(null, viewModel.activeConversationId.value)
        assertTrue(viewModel.messages.value.orEmpty().isEmpty())
        assertBlockedInput()
        var mainActionRan = false
        launch(testDispatcher) { mainActionRan = true }
        testScheduler.runCurrent()
        assertTrue(mainActionRan)
        assertEquals(true, viewModel.isConversationLoading.value)

        preparationScheduler.advanceUntilIdle()
        testScheduler.advanceUntilIdle()
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals(listOf("prompt", "answer"), viewModel.messages.value.orEmpty().map { it.content })
        assertEquals("private reasoning", viewModel.messages.value.orEmpty().last().traceItems.single().content)
        assertFalse(viewModel.isConversationLoading.value == true)
        viewModel.sendMessage("ready")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("ready", match {
            it.map { message -> message.content } == listOf("prompt", "thinking", "answer") &&
                it[1].reasoningText == "private reasoning" && !it[1].includeInPrompt
        }, any(), null, "old", any()) }
    }

    @Test
    fun `changing folders cancels old navigation and replaces history instead of reusing the old store`() = runTest {
        every { conversationStore.storageIdentity } returns "first-root"
        seedRemoteNavigation()
        testScheduler.advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        coEvery { conversationStore.switchActiveConversation("slow") } returns ConversationSummary("slow", "Old", 2L)
        coEvery { conversationStore.loadMessages("slow") } coAnswers { release.await(); emptyList() }
        viewModel.selectConversation("slow")
        testScheduler.runCurrent()

        val replacement: ConversationStore = mockk(relaxed = true)
        every { replacement.storageIdentity } returns "second-root"
        coEvery { replacement.getOrCreateActiveWorkspace() } returns WorkspaceSummary("new-workspace", "New")
        coEvery { replacement.listWorkspaces() } returns listOf(WorkspaceSummary("new-workspace", "New"))
        coEvery { replacement.getOrCreateActiveConversation() } returns ConversationSummary("new-chat", "New", 3L)
        coEvery { replacement.loadMessages("new-chat") } returns listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "New folder"))
        coEvery { replacement.observeConversations(any()) } returns flowOf(emptyList())
        viewModel.initialize(apiKeyStore, repository, replacement)
        testScheduler.advanceUntilIdle()
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("new-workspace", viewModel.activeWorkspaceId.value)
        assertEquals("new-chat", viewModel.activeConversationId.value)
        assertEquals("New folder", viewModel.messages.value!!.single().content)
        assertEquals(false, viewModel.isConversationLoading.value)
    }

    private fun seedNavigation(
        store: ConversationStore = conversationStore,
        id: String = "old",
        workspaceId: String = "ws",
        history: List<StoredChatMessage> = listOf(StoredChatMessage(1L, ChatMessage.ROLE_USER, "history"))
    ) {
        every { store.storageIdentity } returns null
        val workspace = WorkspaceSummary(workspaceId, "Personal")
        val conversation = ConversationSummary(id, id, 1L)
        every { apiKeyStore.hasApiKey() } returns true
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf()
        coEvery { store.getOrCreateActiveWorkspace() } returns workspace
        coEvery { store.listWorkspaces() } returns listOf(workspace)
        coEvery { store.getOrCreateActiveConversation() } returns conversation
        coEvery { store.listConversations() } returns listOf(conversation)
        coEvery { store.loadMessages(id) } returns history
        coEvery { store.observeConversations(workspaceId) } returns flowOf(listOf(conversation))
        coEvery { store.switchActiveWorkspace(workspaceId) } returns workspace
        coEvery { store.switchActiveConversation(id) } returns conversation
    }

    @Test
    fun `restored tool traces retain complete original payloads`() = runTest {
        val arguments = """{"nested":{"query":"${"a".repeat(2_100)}"},"sentinel":"call-end-å🚀"}"""
        val result = """{"nested":{"answer":"${"b".repeat(2_200)}"},"sentinel":"result-end-ö🚀"}"""
        seedNavigation(history = listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_ASSISTANT, arguments,
                kind = ChatMessage.KIND_TOOL_CALL, turnId = "turn", toolCallId = "call",
                toolName = "WebSearchTool", toolArgumentsJson = arguments, searchable = false),
            StoredChatMessage(2L, ChatMessage.ROLE_TOOL, result,
                kind = ChatMessage.KIND_TOOL_RESULT, turnId = "turn", toolCallId = "call",
                toolName = "WebSearchTool", searchable = false),
            StoredChatMessage(3L, ChatMessage.ROLE_ASSISTANT, "Done", turnId = "turn")
        ))
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        val traces = viewModel.messages.value!!.single().traceItems
        assertEquals(arguments, traces.single { it.kind == UiTraceKind.TOOL_CALL }.content)
        assertEquals(result, traces.single { it.kind == UiTraceKind.TOOL_RESULT }.content)
    }

    private fun assertBlockedInput() {
        val before = viewModel.messages.value
        viewModel.sendMessage("blocked")
        viewModel.retryMessage(before.orEmpty().lastOrNull { it.isError }?.id ?: 99L)
        viewModel.allowMoreLoops(7)
        assertEquals(before, viewModel.messages.value)
        assertFalse(viewModel.isLoading.value == true)
        verify(exactly = 0) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
    }

    private fun seedFailedRun() {
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("failed response")
        }
        viewModel.sendMessage("failed prompt")
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.messages.value.orEmpty().last().isError)
        clearMocks(repository, answers = false)
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf()
    }

    private class RecordingPowerKeeper : ChatRunPowerKeeper {
        var acquireCalls = 0
        var releaseCalls = 0
        override fun acquire() { acquireCalls++ }
        override fun release() { releaseCalls++ }
    }

    private class QuestionRun {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val answers = mutableListOf<AskUserAnswer>()
    }

    private fun installQuestionRun(
        questions: List<AskUserRequest> = listOf(AskUserRequest("Which project?", listOf("Personal", "Work")))
    ): QuestionRun {
        val run = QuestionRun()
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } answers {
            val handler = lastArg<AskUserHandler>()
            kotlinx.coroutines.flow.flow {
                emit(ChatStreamEvent.PassStarted(0))
                run.entered.complete(Unit)
                try {
                    questions.forEach { request -> run.answers += handler.askUser(request) }
                    run.finish.await()
                    emit(ChatStreamEvent.Completed(ChatTurnResult(
                        content = "Done.",
                        toolCalls = emptyList(),
                        intermediateMessages = listOf(
                            CoreChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "Done.")
                        )
                    )))
                } finally {
                    run.cancelled.complete(Unit)
                }
            }
        }
        return run
    }

    @Test
    fun `question keeps run busy blocks navigation and persists only after completion`() = runTest {
        seedNavigation()
        val keeper = RecordingPowerKeeper()
        val run = installQuestionRun()
        viewModel.initialize(apiKeyStore, repository, conversationStore, chatRunPowerKeeper = keeper)
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("Clarify this")
        testScheduler.runCurrent()

        assertTrue(run.entered.isCompleted)
        val question = viewModel.pendingUserQuestion.value!!
        assertEquals("Which project?", question.request.question)
        assertEquals(true, viewModel.isLoading.value)
        assertEquals(1, keeper.acquireCalls)
        assertEquals(1, keeper.releaseCalls)
        val messages = viewModel.messages.value
        viewModel.sendMessage("Must not start another run")
        viewModel.selectConversation("other")
        viewModel.requestNewChat()
        testScheduler.runCurrent()
        assertEquals(messages, viewModel.messages.value)
        assertEquals("old", viewModel.activeConversationId.value)
        verify(exactly = 1) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationStore.switchActiveConversation("other") }
        coVerify(exactly = 0) { conversationStore.createConversation() }
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }
        coVerify(exactly = 0) { conversationStore.appendMessage(any(), any(), any()) }

        viewModel.selectUserQuestionOption(question.id, "Work")
        assertEquals("Work", viewModel.pendingUserQuestion.value!!.selectedOption)
        assertTrue(run.answers.isEmpty())
        viewModel.submitUserQuestion(question.id)
        assertEquals(null, viewModel.pendingUserQuestion.value)
        testScheduler.runCurrent()
        assertEquals(listOf(AskUserAnswer.Option("Work")), run.answers)
        assertEquals(true, viewModel.isLoading.value)
        assertEquals(2, keeper.acquireCalls)
        assertEquals(1, keeper.releaseCalls)
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }

        run.finish.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.isLoading.value)
        assertEquals(2, keeper.releaseCalls)
        coVerify(exactly = 1) { conversationStore.appendMessages("old", match { it.size == 2 }) }
    }

    @Test
    fun `draft selection blanks and stale submissions cannot answer the next question`() = runTest {
        seedNavigation()
        val request = AskUserRequest("Choose", listOf("Personal", "Work"))
        val run = installQuestionRun(listOf(request, request, AskUserRequest("Anything else?")))
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("Ask three")
        testScheduler.runCurrent()
        val first = viewModel.pendingUserQuestion.value!!.id
        viewModel.selectUserQuestionOption(first, "Unknown")
        viewModel.updateUserQuestionText(first, " \n ")
        viewModel.submitUserQuestion(first)
        assertTrue(run.answers.isEmpty())
        assertEquals(first, viewModel.pendingUserQuestion.value!!.id)
        viewModel.selectUserQuestionOption(first, "Personal")
        assertEquals("", viewModel.pendingUserQuestion.value!!.customText)
        val text = "  My \"home\"\nlist — å🚀  "
        viewModel.updateUserQuestionText(first, text)
        assertEquals(null, viewModel.pendingUserQuestion.value!!.selectedOption)
        assertEquals(text, viewModel.pendingUserQuestion.value!!.customText)
        viewModel.submitUserQuestion(first)
        viewModel.submitUserQuestion(first)
        testScheduler.runCurrent()
        assertEquals(listOf(AskUserAnswer.Text(text.trim())), run.answers)

        val second = viewModel.pendingUserQuestion.value!!.id
        assertTrue(second > first)
        viewModel.selectUserQuestionOption(first, "Work")
        viewModel.updateUserQuestionText(first, "stale")
        viewModel.cancelUserQuestion(first)
        viewModel.submitUserQuestion(first)
        assertEquals(second, viewModel.pendingUserQuestion.value!!.id)
        assertEquals("", viewModel.pendingUserQuestion.value!!.customText)
        viewModel.updateUserQuestionText(second, "discard me")
        viewModel.selectUserQuestionOption(second, "Work")
        assertEquals("", viewModel.pendingUserQuestion.value!!.customText)
        viewModel.submitUserQuestion(second)
        testScheduler.runCurrent()
        val third = viewModel.pendingUserQuestion.value!!
        assertTrue(third.request.options.isEmpty())
        viewModel.cancelUserQuestion(third.id)
        testScheduler.runCurrent()
        assertEquals(listOf(AskUserAnswer.Text(text.trim()), AskUserAnswer.Option("Work"),
            AskUserAnswer.Cancelled), run.answers)
        assertEquals(null, viewModel.pendingUserQuestion.value)
        assertEquals(true, viewModel.isLoading.value)
        run.finish.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `same resolved root retains pending question and draft across reinitialization`() = runTest {
        seedNavigation()
        every { conversationStore.storageIdentity } returns "/same/root"
        val run = installQuestionRun()
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("Keep the question")
        testScheduler.runCurrent()
        val id = viewModel.pendingUserQuestion.value!!.id
        viewModel.updateUserQuestionText(id, "unfinished\nanswer")
        val retained = viewModel.pendingUserQuestion.value
        val sameRoot: ConversationStore = mockk(relaxed = true)
        every { sameRoot.storageIdentity } returns "/same/root"
        viewModel.initialize(apiKeyStore, repository, sameRoot)
        testScheduler.runCurrent()
        assertEquals(retained, viewModel.pendingUserQuestion.value)
        assertEquals(true, viewModel.isLoading.value)
        assertTrue(run.answers.isEmpty())
        coVerify(exactly = 0) { sameRoot.loadMessages(any()) }
        viewModel.submitUserQuestion(id)
        testScheduler.runCurrent()
        assertEquals(listOf(AskUserAnswer.Text("unfinished\nanswer")), run.answers)
        run.finish.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `Stop cancels waiting without reacquiring power and stale actions cannot answer later run`() = runTest {
        seedNavigation()
        val keeper = RecordingPowerKeeper()
        val stopped = installQuestionRun()
        viewModel.initialize(apiKeyStore, repository, conversationStore, chatRunPowerKeeper = keeper)
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("Stop this question")
        testScheduler.runCurrent()
        val obsoleteId = viewModel.pendingUserQuestion.value!!.id
        viewModel.stopGeneration()
        testScheduler.advanceUntilIdle()
        assertTrue(stopped.cancelled.isCompleted)
        assertTrue(stopped.answers.isEmpty())
        assertEquals(null, viewModel.pendingUserQuestion.value)
        assertEquals(1, keeper.acquireCalls)
        assertEquals(1, keeper.releaseCalls)
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }

        val later = installQuestionRun()
        viewModel.sendMessage("A later question")
        testScheduler.runCurrent()
        val currentId = viewModel.pendingUserQuestion.value!!.id
        assertTrue(currentId > obsoleteId)
        viewModel.cancelUserQuestion(obsoleteId)
        viewModel.updateUserQuestionText(obsoleteId, "obsolete")
        viewModel.submitUserQuestion(obsoleteId)
        assertEquals(currentId, viewModel.pendingUserQuestion.value!!.id)
        assertTrue(later.answers.isEmpty())
        viewModel.cancelUserQuestion(currentId)
        testScheduler.runCurrent()
        assertEquals(listOf(AskUserAnswer.Cancelled), later.answers)
        later.finish.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(3, keeper.acquireCalls)
        assertEquals(3, keeper.releaseCalls)
    }

    @Test
    fun `root replacement clears only old waiter and never reacquires its power lease`() = runTest {
        seedNavigation()
        every { conversationStore.storageIdentity } returns "/old/root"
        val oldKeeper = RecordingPowerKeeper()
        val obsolete = installQuestionRun()
        viewModel.initialize(apiKeyStore, repository, conversationStore, chatRunPowerKeeper = oldKeeper)
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("Old root question")
        testScheduler.runCurrent()
        val oldId = viewModel.pendingUserQuestion.value!!.id

        val replacement: ConversationStore = mockk(relaxed = true)
        seedNavigation(replacement, "replacement", "replacement-ws")
        every { replacement.storageIdentity } returns "/new/root"
        val newKeeper = RecordingPowerKeeper()
        viewModel.initialize(apiKeyStore, repository, replacement, chatRunPowerKeeper = newKeeper)
        assertEquals(null, viewModel.pendingUserQuestion.value)
        testScheduler.advanceUntilIdle()
        assertTrue(obsolete.cancelled.isCompleted)
        assertTrue(obsolete.answers.isEmpty())
        assertEquals(1, oldKeeper.acquireCalls)
        assertEquals(1, oldKeeper.releaseCalls)

        val current = installQuestionRun()
        viewModel.sendMessage("New root question")
        testScheduler.runCurrent()
        val currentId = viewModel.pendingUserQuestion.value!!.id
        assertTrue(currentId > oldId)
        viewModel.selectUserQuestionOption(oldId, "Work")
        viewModel.submitUserQuestion(oldId)
        viewModel.cancelUserQuestion(oldId)
        assertEquals(currentId, viewModel.pendingUserQuestion.value!!.id)
        assertTrue(current.answers.isEmpty())
        viewModel.cancelUserQuestion(currentId)
        testScheduler.runCurrent()
        current.finish.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(AskUserAnswer.Cancelled), current.answers)
        assertEquals(2, newKeeper.acquireCalls)
        assertEquals(2, newKeeper.releaseCalls)
        coVerify(exactly = 0) { conversationStore.appendMessages(any(), any()) }
        coVerify(exactly = 1) { replacement.appendMessages("replacement", any()) }
    }

    @Test
    fun `pending selection blocks retry without removing failed messages or arming continuation`() = runTest {
        seedNavigation(history = listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "failed prompt"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "failed response", isError = true)
        ))
        val loading = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.switchActiveConversation("selected") } returns ConversationSummary("selected", "Selected", 2L)
        coEvery { conversationStore.loadMessages("selected") } coAnswers { loading.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        seedFailedRun()
        viewModel.selectConversation("selected")
        assertEquals(true, viewModel.isConversationLoading.value)
        assertBlockedInput()
        testScheduler.runCurrent()
        assertBlockedInput()
        loading.complete(emptyList())
        testScheduler.advanceUntilIdle()
        viewModel.sendMessage("after selection")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("after selection", emptyList(), any(), null, "selected", any()) }
    }

    @Test
    fun `new chat creation failure restores history and real persistent selection`() = runTest {
        seedNavigation()
        val creation = CompletableDeferred<ConversationSummary>()
        coEvery { conversationStore.createConversation() } coAnswers { creation.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        viewModel.requestNewChat()
        assertTrue(viewModel.messages.value.orEmpty().isEmpty())
        testScheduler.runCurrent()
        creation.completeExceptionally(IllegalStateException("creation failed"))
        testScheduler.advanceUntilIdle()
        assertEquals(before, viewModel.messages.value)
        assertEquals("old", viewModel.activeConversationId.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        assertTrue(viewModel.storageError.value.orEmpty().contains("creation failed"))
        coVerify { conversationStore.switchActiveWorkspace("ws") }
        coVerify { conversationStore.switchActiveConversation("old") }
    }

    @Test
    fun `selections serialize message reads and commit matching prompt history`() = runTest {
        seedNavigation()
        val second = CompletableDeferred<List<StoredChatMessage>>()
        val third = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.switchActiveConversation("second") } returns ConversationSummary("second", "Second", 2L)
        coEvery { conversationStore.switchActiveConversation("third") } returns ConversationSummary("third", "Third", 3L)
        coEvery { conversationStore.loadMessages("second") } coAnswers { second.await() }
        coEvery { conversationStore.loadMessages("third") } coAnswers { third.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        viewModel.selectConversation("second")
        testScheduler.runCurrent()
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals("history", viewModel.messages.value.orEmpty().single().content)
        viewModel.selectConversation("third")
        testScheduler.runCurrent()
        coVerify(exactly = 0) { conversationStore.switchActiveConversation("third") }
        coVerify(exactly = 0) { conversationStore.loadMessages("third") }
        second.complete(listOf(StoredChatMessage(2L, ChatMessage.ROLE_USER, "second history")))
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        third.complete(listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "third history")))
        testScheduler.advanceUntilIdle()
        assertEquals("third", viewModel.activeConversationId.value)
        assertEquals("third history", viewModel.messages.value.orEmpty().single().content)
        viewModel.sendMessage("next")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("next", match { it.single().content == "third history" }, any(), null, "third", any()) }
    }

    @Test
    fun `failed workspace load rolls persistent selections back without rereading old history`() = runTest {
        seedNavigation()
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        val work = WorkspaceSummary("work", "Work")
        coEvery { conversationStore.switchActiveWorkspace("work") } returns work
        coEvery { conversationStore.getOrCreateActiveConversation() } returns ConversationSummary("work-chat", "Work chat", 2L)
        coEvery { conversationStore.listWorkspaces() } returns listOf(WorkspaceSummary("ws", "Personal"), work)
        coEvery { conversationStore.loadMessages("work-chat") } throws IllegalStateException("work history unavailable")
        coEvery { conversationStore.loadMessages("old") } throws IllegalStateException("old provider also unavailable")
        viewModel.switchWorkspace("work")
        testScheduler.advanceUntilIdle()
        assertEquals("ws", viewModel.activeWorkspaceId.value)
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals(before, viewModel.messages.value)
        assertEquals(listOf(UiWorkspace("ws", "Personal")), viewModel.workspaces.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        assertTrue(viewModel.storageError.value.orEmpty().contains("work history unavailable"))
        coVerifyOrder {
            conversationStore.switchActiveWorkspace("work")
            conversationStore.loadMessages("work-chat")
            conversationStore.switchActiveWorkspace("ws")
            conversationStore.switchActiveConversation("old")
        }
        coVerify(exactly = 1) { conversationStore.loadMessages("old") }
        viewModel.sendMessage("still personal")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("still personal", match { it.single().content == "history" }, any(), null, "old", any()) }
    }

    @Test
    fun `failed selection restores exact visible traces and persisted prompt context`() = runTest {
        seedNavigation(history = listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "history"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "thinking", kind = ChatMessage.KIND_REASONING,
                turnId = "turn", reasoningText = "private reasoning", includeInPrompt = false, searchable = false),
            StoredChatMessage(3L, ChatMessage.ROLE_ASSISTANT, "answer", turnId = "turn")
        ))
        coEvery { conversationStore.switchActiveConversation("broken") } returns ConversationSummary("broken", "Broken", 2L)
        coEvery { conversationStore.loadMessages("broken") } throws IllegalStateException("message read failed")
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        assertTrue(before.orEmpty().last().traceItems.isNotEmpty())
        viewModel.selectConversation("broken")
        testScheduler.advanceUntilIdle()
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals("ws", viewModel.activeWorkspaceId.value)
        assertEquals(before, viewModel.messages.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        assertTrue(viewModel.storageError.value.orEmpty().contains("message read failed"))
        coVerifyOrder {
            conversationStore.switchActiveConversation("broken")
            conversationStore.loadMessages("broken")
            conversationStore.switchActiveWorkspace("ws")
            conversationStore.switchActiveConversation("old")
        }
        viewModel.sendMessage("next")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("next", match {
            it.map { message -> message.content } == listOf("history", "thinking", "answer") &&
                it[1].reasoningText == "private reasoning" && !it[1].includeInPrompt
        }, any(), null, "old", any()) }
    }

    @Test
    fun `new chat load failure restores immediately cleared history and previous selection`() = runTest {
        seedNavigation()
        val failure = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.createConversation() } returns ConversationSummary("new", null, 2L)
        coEvery { conversationStore.loadMessages("new") } coAnswers { failure.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        viewModel.requestNewChat()
        assertTrue(viewModel.messages.value.orEmpty().isEmpty())
        testScheduler.runCurrent()
        failure.completeExceptionally(IllegalStateException("new chat unavailable"))
        testScheduler.advanceUntilIdle()
        assertEquals(before, viewModel.messages.value)
        assertEquals("old", viewModel.activeConversationId.value)
        coVerify { conversationStore.switchActiveWorkspace("ws") }
        coVerify { conversationStore.switchActiveConversation("old") }
        assertFalse(viewModel.isConversationLoading.value == true)
        assertTrue(viewModel.storageError.value.orEmpty().contains("new chat unavailable"))
        viewModel.sendMessage("recovered")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("recovered", match { it.single().content == "history" }, any(), null, "old", any()) }
    }

    @Test
    fun `rollback failure blocks send retry and continuation until successful reload`() = runTest {
        seedNavigation(history = listOf(
            StoredChatMessage(1L, ChatMessage.ROLE_USER, "history"),
            StoredChatMessage(2L, ChatMessage.ROLE_ASSISTANT, "failed", isError = true)
        ))
        coEvery { conversationStore.switchActiveConversation("broken") } returns ConversationSummary("broken", "Broken", 2L)
        coEvery { conversationStore.loadMessages("broken") } throws IllegalStateException("read failed")
        coEvery { conversationStore.switchActiveConversation("old") } throws IllegalStateException("rollback failed")
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        seedFailedRun()
        viewModel.selectConversation("broken")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.storageError.value.orEmpty().contains("read failed"))
        assertTrue(viewModel.storageError.value.orEmpty().contains("rollback failed"))
        assertBlockedInput()
        testScheduler.runCurrent()
        assertBlockedInput()
        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()
        assertEquals(null, viewModel.storageError.value)
        viewModel.sendMessage("recovered")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("recovered", any(), any(), null, "old", any()) }
    }

    @Test
    fun `replacement root cancels old reads and old completion cannot clear new barrier`() = runTest {
        seedNavigation()
        every { conversationStore.storageIdentity } returns "/old/root"
        val oldMessages = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.switchActiveConversation("slow") } returns ConversationSummary("slow", "Slow", 2L)
        coEvery { conversationStore.loadMessages("slow") } coAnswers { oldMessages.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        viewModel.selectConversation("slow")
        testScheduler.runCurrent()

        val replacement: ConversationStore = mockk(relaxed = true)
        val replacementRepository: ChatRepository = mockk(relaxed = true)
        val newMessages = CompletableDeferred<List<StoredChatMessage>>()
        val newFlow = MutableSharedFlow<List<ConversationSummary>>()
        seedNavigation(replacement, "replacement", "replacement-ws")
        every { replacement.storageIdentity } returns "/replacement/root"
        coEvery { replacement.loadMessages("replacement") } coAnswers { newMessages.await() }
        coEvery { replacement.observeConversations("replacement-ws") } returns newFlow
        every { replacementRepository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf()
        viewModel.initialize(apiKeyStore, replacementRepository, replacement)
        assertEquals(true, viewModel.isConversationLoading.value)
        viewModel.selectConversation("must-not-overtake")
        viewModel.retryChatHistoryLoad()
        testScheduler.runCurrent()
        oldMessages.complete(listOf(StoredChatMessage(2L, ChatMessage.ROLE_USER, "stale")))
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        assertFalse(viewModel.messages.value.orEmpty().any { it.content == "stale" })
        coVerify(exactly = 0) { replacement.switchActiveConversation("must-not-overtake") }
        coVerify(exactly = 1) { replacement.loadMessages("replacement") }
        newMessages.complete(listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "new root history")))
        testScheduler.runCurrent()
        newFlow.emit(listOf(ConversationSummary("replacement", "New root", 3L)))
        testScheduler.runCurrent()
        assertEquals("replacement", viewModel.activeConversationId.value)
        assertEquals("replacement-ws", viewModel.activeWorkspaceId.value)
        assertEquals("replacement", viewModel.conversations.value.orEmpty().single().id)
        assertEquals(null, viewModel.storageError.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        viewModel.sendMessage("new root send")
        testScheduler.advanceUntilIdle()
        verify { replacementRepository.streamMessage("new root send", match { it.single().content == "new root history" }, any(), null, "replacement", any()) }
        verify(exactly = 0) { repository.streamMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `same store reinitialization preserves chat without a fresh creation`() = runTest {
        seedNavigation()
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        viewModel.initialize(apiKeyStore, repository, conversationStore, startWithNewConversation = true)
        testScheduler.advanceUntilIdle()
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals(before, viewModel.messages.value)
        coVerify(exactly = 0) { conversationStore.createConversation() }
        coVerify(exactly = 1) { conversationStore.getOrCreateActiveConversation() }
    }

    @Test
    fun `equal resolved roots preserve committed chat across distinct store instances`() = runTest {
        seedNavigation()
        every { conversationStore.storageIdentity } returns "/resolved/root"
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val before = viewModel.messages.value
        val sameRoot: ConversationStore = mockk(relaxed = true)
        every { sameRoot.storageIdentity } returns "/resolved/root"
        viewModel.initialize(apiKeyStore, repository, sameRoot, startWithNewConversation = true)
        testScheduler.advanceUntilIdle()
        assertEquals("old", viewModel.activeConversationId.value)
        assertEquals(before, viewModel.messages.value)
        coVerify(exactly = 0) { sameRoot.createConversation() }
        coVerify(exactly = 0) { sameRoot.getOrCreateActiveConversation() }
        viewModel.sendMessage("still committed")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("still committed", match { it.single().content == "history" }, any(), null, "old", any()) }
    }

    @Test
    fun `failed replacement retries only new root without allocating another chat`() = runTest {
        seedNavigation()
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        val replacement: ConversationStore = mockk(relaxed = true)
        seedNavigation(replacement, "replacement", "replacement-ws")
        coEvery { replacement.createConversation() } returns ConversationSummary("replacement", null, 2L)
        coEvery { replacement.loadMessages("replacement") } throws IllegalStateException("replacement unavailable")
        viewModel.initialize(apiKeyStore, repository, replacement, startWithNewConversation = true)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.storageError.value.orEmpty().contains("replacement unavailable"))
        assertBlockedInput()
        coEvery { replacement.loadMessages("replacement") } returns listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "recovered root"))
        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()
        assertEquals("replacement", viewModel.activeConversationId.value)
        assertEquals("recovered root", viewModel.messages.value.orEmpty().single().content)
        assertEquals(null, viewModel.storageError.value)
        coVerify(exactly = 1) { replacement.createConversation() }
        coVerify(exactly = 1) { conversationStore.loadMessages("old") }
    }

    @Test
    fun `replacement releases the acquired old power keeper before becoming ready`() = runTest {
        seedNavigation()
        val oldKeeper = RecordingPowerKeeper()
        val newKeeper = RecordingPowerKeeper()
        val streamStarted = CompletableDeferred<Unit>()
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            streamStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        viewModel.initialize(apiKeyStore, repository, conversationStore, chatRunPowerKeeper = oldKeeper)
        testScheduler.runCurrent()
        viewModel.sendMessage("running")
        testScheduler.runCurrent()
        assertTrue(streamStarted.isCompleted)
        assertEquals(1, oldKeeper.acquireCalls)
        val replacement: ConversationStore = mockk(relaxed = true)
        seedNavigation(replacement, "replacement", "replacement-ws")
        viewModel.initialize(apiKeyStore, repository, replacement, chatRunPowerKeeper = newKeeper)
        testScheduler.advanceUntilIdle()
        assertEquals(1, oldKeeper.releaseCalls)
        assertEquals(0, newKeeper.releaseCalls)
        assertEquals("replacement", viewModel.activeConversationId.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        viewModel.sendMessage("after replacement")
        testScheduler.advanceUntilIdle()
        assertEquals(1, newKeeper.acquireCalls)
        assertEquals(1, newKeeper.releaseCalls)
        assertEquals(1, oldKeeper.releaseCalls)
    }

    @Test
    fun `active deletion survivor failure never makes deleted chat sendable`() = runTest {
        seedNavigation()
        val survivor = ConversationSummary("survivor", "Survivor", 2L)
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        coEvery { conversationStore.listConversations() } returns listOf(survivor)
        coEvery { conversationStore.getOrCreateActiveConversation() } returns survivor
        coEvery { conversationStore.switchActiveConversation("survivor") } returns survivor
        coEvery { conversationStore.loadMessages("survivor") } throws IllegalStateException("survivor unavailable")
        viewModel.deleteConversation("old")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.deletingConversationIds.value.orEmpty().isEmpty())
        assertTrue(viewModel.storageError.value.orEmpty().contains("survivor unavailable"))
        coVerify(exactly = 0) { conversationStore.switchActiveConversation("old") }
        assertBlockedInput()
        coEvery { conversationStore.loadMessages("survivor") } returns listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "survivor history"))
        viewModel.retryChatHistoryLoad()
        testScheduler.advanceUntilIdle()
        assertEquals("survivor", viewModel.activeConversationId.value)
        assertEquals(null, viewModel.storageError.value)
        viewModel.sendMessage("safe")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("safe", match { it.single().content == "survivor history" }, any(), null, "survivor", any()) }
    }

    @Test
    fun `root replacement cancels deletion progress without reporting cancellation as storage error`() = runTest {
        seedNavigation()
        val deleteStarted = CompletableDeferred<Unit>()
        val deletion = CompletableDeferred<Unit>()
        coEvery { conversationStore.deleteConversation("old") } coAnswers {
            deleteStarted.complete(Unit)
            deletion.await()
        }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        viewModel.deleteConversation("old")
        testScheduler.runCurrent()
        assertTrue(deleteStarted.isCompleted)
        assertTrue("old" in viewModel.deletingConversationIds.value.orEmpty())
        val replacement: ConversationStore = mockk(relaxed = true)
        seedNavigation(replacement, "replacement", "replacement-ws")
        viewModel.initialize(apiKeyStore, repository, replacement)
        testScheduler.advanceUntilIdle()
        deletion.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.deletingConversationIds.value.orEmpty().isEmpty())
        assertEquals(null, viewModel.storageError.value)
        assertEquals("replacement", viewModel.activeConversationId.value)
        assertFalse(viewModel.isConversationLoading.value == true)
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
    fun `new chat clears synchronously and blocks input through creation and message loading`() = runTest {
        seedNavigation()
        val creation = CompletableDeferred<ConversationSummary>()
        val loading = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.createConversation() } coAnswers { creation.await() }
        coEvery { conversationStore.loadMessages("new") } coAnswers { loading.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()

        viewModel.requestNewChat()
        assertEquals(emptyList<UiChatMessage>(), viewModel.messages.value)
        assertEquals(true, viewModel.isConversationLoading.value)
        assertBlockedInput()
        viewModel.requestNewChat()
        testScheduler.runCurrent()
        assertBlockedInput()
        creation.complete(ConversationSummary("new", null, 2L))
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        assertBlockedInput()
        loading.complete(emptyList())
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationStore.createConversation() }
        assertEquals("new", viewModel.activeConversationId.value)
        assertFalse(viewModel.isConversationLoading.value == true)
        viewModel.sendMessage("ready")
        testScheduler.advanceUntilIdle()
        verify { repository.streamMessage("ready", emptyList(), any(), null, "new", any()) }
    }

    @Test
    fun `new chat and subsequent selection are FIFO with one continuous loading barrier`() = runTest {
        seedNavigation()
        val creation = CompletableDeferred<ConversationSummary>()
        val selectedMessages = CompletableDeferred<List<StoredChatMessage>>()
        coEvery { conversationStore.createConversation() } coAnswers { creation.await() }
        coEvery { conversationStore.loadMessages("new") } returns emptyList()
        coEvery { conversationStore.switchActiveConversation("selected") } returns ConversationSummary("selected", "Selected", 3L)
        coEvery { conversationStore.loadMessages("selected") } coAnswers { selectedMessages.await() }
        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()

        viewModel.requestNewChat()
        testScheduler.runCurrent()
        viewModel.selectConversation("selected")
        testScheduler.runCurrent()
        coVerify(exactly = 0) { conversationStore.switchActiveConversation("selected") }
        creation.complete(ConversationSummary("new", null, 2L))
        testScheduler.runCurrent()
        assertEquals(true, viewModel.isConversationLoading.value)
        coVerifyOrder {
            conversationStore.createConversation()
            conversationStore.loadMessages("new")
            conversationStore.switchActiveConversation("selected")
            conversationStore.loadMessages("selected")
        }
        selectedMessages.complete(listOf(StoredChatMessage(3L, ChatMessage.ROLE_USER, "selected history")))
        testScheduler.advanceUntilIdle()
        assertEquals("selected", viewModel.activeConversationId.value)
        assertEquals("selected history", viewModel.messages.value.orEmpty().single().content)
        assertFalse(viewModel.isConversationLoading.value == true)
    }
    @Test
    fun `conversation list loading remains true until the active workspace emits`() = runTest {
        val workspace = WorkspaceSummary("1", "Personal")
        val conversation = ConversationSummary("1", "Loaded", System.currentTimeMillis())
        val conversationFlow = MutableSharedFlow<List<ConversationSummary>>()

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns workspace
        coEvery { conversationStore.listWorkspaces() } returns listOf(workspace)
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.observeConversations("1") } returns conversationFlow

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()

        assertEquals(true, viewModel.isConversationListLoading.value)

        conversationFlow.emit(emptyList())
        testScheduler.runCurrent()

        assertEquals(false, viewModel.isConversationListLoading.value)
        assertEquals(emptyList<UiConversation>(), viewModel.conversations.value)
    }

    @Test
    fun `workspace list loading ignores a replaced workspace observer`() = runTest {
        val personal = WorkspaceSummary("1", "Personal")
        val work = WorkspaceSummary("2", "Work")
        val personalConversation = ConversationSummary("1", "Personal chat", System.currentTimeMillis())
        val workConversation = ConversationSummary("2", "Work chat", System.currentTimeMillis())
        val personalFlow = MutableSharedFlow<List<ConversationSummary>>()
        val workFlow = MutableSharedFlow<List<ConversationSummary>>()

        coEvery { conversationStore.getOrCreateActiveWorkspace() } returns personal
        coEvery { conversationStore.listWorkspaces() } returns listOf(personal, work)
        coEvery { conversationStore.getOrCreateActiveConversation() } returnsMany listOf(
            personalConversation,
            workConversation
        )
        coEvery { conversationStore.loadMessages(any()) } returns emptyList()
        coEvery { conversationStore.observeConversations("1") } returns personalFlow
        coEvery { conversationStore.observeConversations("2") } returns workFlow
        coEvery { conversationStore.switchActiveWorkspace("2") } returns work

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.runCurrent()
        personalFlow.emit(listOf(personalConversation))
        testScheduler.runCurrent()

        viewModel.switchWorkspace("2")
        testScheduler.runCurrent()

        assertEquals(true, viewModel.isConversationListLoading.value)

        workFlow.emit(listOf(workConversation))
        testScheduler.runCurrent()

        assertEquals(false, viewModel.isConversationListLoading.value)
        assertEquals("2", viewModel.conversations.value.orEmpty().single().id)
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

        viewModel.requestNewChat()
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.createConversation() }
    }

    @Test
    fun `local Gemma does not show the OpenRouter key warning`() {
        every { apiKeyStore.loadModel() } returns LocalGemmaModelStore.LOCAL_MODEL_ID
        every { apiKeyStore.hasApiKey() } returns false

        viewModel.initialize(apiKeyStore, repository, conversationStore)

        assertEquals(false, viewModel.needsApiKey.value)
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

        viewModel.requestNewChat()
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

        viewModel.requestNewChat()
        viewModel.requestNewChat()
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flow

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("Keep this response in the current chat")
        viewModel.requestNewChat()
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
    fun `deleteConversation exposes immediate progress and ignores repeated requests`() = runTest {
        val initialConversation = ConversationSummary("1", "First Chat", System.currentTimeMillis())
        val deleteStarted = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns emptyList()
        coEvery { conversationStore.deleteConversation("1") } coAnswers {
            deleteStarted.complete(Unit)
            finishDelete.await()
        }
        coEvery { conversationStore.createConversation() } returns ConversationSummary("2", null, 2L)
        coEvery { conversationStore.loadMessages("2") } returns emptyList()

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()

        viewModel.deleteConversation("1")
        assertTrue("1" in viewModel.deletingConversationIds.value.orEmpty())
        viewModel.deleteConversation("1")
        testScheduler.runCurrent()
        assertTrue(deleteStarted.isCompleted)

        finishDelete.complete(Unit)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationStore.deleteConversation("1") }
        assertTrue(viewModel.deletingConversationIds.value.orEmpty().isEmpty())
        assertEquals("2", viewModel.activeConversationId.value)
    }

    @Test
    fun `failed deletion restores row state and exposes an error`() = runTest {
        val initialConversation = ConversationSummary("1", "First Chat", System.currentTimeMillis())
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(initialConversation)
        coEvery { conversationStore.deleteConversation("1") } throws IllegalStateException("disk unavailable")

        viewModel.initialize(apiKeyStore, repository, conversationStore)
        testScheduler.advanceUntilIdle()
        viewModel.deleteConversation("1")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.deletingConversationIds.value.orEmpty().isEmpty())
        assertTrue(viewModel.storageError.value.orEmpty().contains("disk unavailable"))
        assertEquals("1", viewModel.activeConversationId.value)
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

        var deleted = false
        coEvery { conversationStore.getOrCreateActiveWorkspace() } coAnswers {
            if (deleted) workspace2 else workspace1
        }
        coEvery { conversationStore.listWorkspaces() } coAnswers {
            if (deleted) listOf(workspace2) else listOf(workspace1, workspace2)
        }
        coEvery { conversationStore.deleteWorkspace("1") } coAnswers { deleted = true }
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversationInW2
        coEvery { conversationStore.loadMessages("3") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversationInW2)

        // Act
        val deletedMemoryWorkspaces = mutableListOf<String>()
        viewModel.initialize(
            apiKeyStore, repository, conversationStore,
            workspaceDeletionHandler = { deletedMemoryWorkspaces += it }
        )
        testScheduler.advanceUntilIdle()

        viewModel.deleteWorkspace("1")
        testScheduler.advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { conversationStore.deleteWorkspace("1") }
        coVerify(atLeast = 1) { conversationStore.getOrCreateActiveWorkspace() }
        assertEquals(listOf("1"), deletedMemoryWorkspaces)
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
        val arguments = """{"query":"Kotlin release","nested":{"value":"${"a".repeat(2_100)}"},"sentinel":"call-end-å🚀"}"""
        val result = """{"nested":{"answer":"${"b".repeat(2_200)}"},"sentinel":"result-end-ö🚀"}"""

        every { apiKeyStore.hasApiKey() } returns true
        coEvery { conversationStore.getOrCreateActiveConversation() } returns conversation
        coEvery { conversationStore.loadMessages("1") } returns emptyList()
        coEvery { conversationStore.listConversations() } returns listOf(conversation)
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
                argumentsChunk = arguments
            ),
            ChatStreamEvent.ToolCallRequested(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                argumentsJson = arguments
            ),
            ChatStreamEvent.ToolResultAvailable(
                passIndex = 0,
                toolCallId = "call-1",
                name = "WebSearchTool",
                result = result,
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
                            content = arguments,
                            kind = CoreChatMessageKind.TOOL_CALL,
                            toolCallId = "call-1",
                            toolName = "WebSearchTool",
                            toolArgumentsJson = arguments,
                            searchable = false
                        ),
                        CoreChatMessage(
                            role = ChatMessage.ROLE_TOOL,
                            content = result,
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
        assertEquals(arguments, assistant.traceItems.single { it.kind == UiTraceKind.TOOL_CALL }.content)
        assertEquals(result, assistant.traceItems.single { it.kind == UiTraceKind.TOOL_RESULT }.content)
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
                result = "Sunny",
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flowOf(
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flow

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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
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
        every { repository.streamMessage(any(), any(), any(), any(), any(), any()) } returns flow

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

        viewModel.requestNewChat()
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
