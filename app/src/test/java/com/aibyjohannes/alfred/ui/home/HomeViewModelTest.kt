package com.aibyjohannes.alfred.ui.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationSummary
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
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
    fun `createConversationAndSwitch creates new conversation if current is non-empty`() = runTest {
        // Arrange
        val initialConversation = ConversationSummary(1L, "First Chat", System.currentTimeMillis())
        val newConversation = ConversationSummary(2L, null, System.currentTimeMillis())

        every { apiKeyStore.hasApiKey() } returns true
        every { apiKeyStore.loadModel() } returns "google/gemini-3.1-flash-lite-preview"
        coEvery { conversationStore.getOrCreateActiveConversation() } returns initialConversation
        coEvery { conversationStore.loadMessages(1L) } returns listOf(
            mockk {
                every { id } returns 1L
                every { role } returns "user"
                every { content } returns "Hello"
            }
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
}
