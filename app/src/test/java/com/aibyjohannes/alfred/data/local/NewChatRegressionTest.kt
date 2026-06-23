package com.aibyjohannes.alfred.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.ui.home.HomeViewModel
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NewChatRegressionTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val apiKeyStore: ApiKeyStore = mockk(relaxed = true)
    private val repository: ChatRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun waitForViewModelLoad(
        viewModel: HomeViewModel, 
        testScheduler: TestCoroutineScheduler,
        targetToChangeFrom: String?,
        expectedMessageCount: Int
    ) {
        var retries = 150
        while ((viewModel.activeConversationId.value == targetToChangeFrom || viewModel.messages.value?.size != expectedMessageCount) && retries > 0) {
            testScheduler.advanceUntilIdle()
            Thread.sleep(20)
            retries--
        }
    }

    @Test
    fun testNewChatIsCreateOnlyRegression() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. Build the real Room database in-memory
        val db = Room.inMemoryDatabaseBuilder(context, ConversationIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        val index = RoomConversationIndex(db)
        val tempDir = temporaryFolder.newFolder()
        val store = FileConversationStore(FileChatHistoryStorage(tempDir), ObjectMapper(), index)

        // Seed conversation A with messages in workspace W
        val ws = store.getOrCreateActiveWorkspace()
        val chatA = store.getOrCreateActiveConversation()
        store.appendMessage(chatA.id, ChatMessage.ROLE_USER, "Hello from conversation A")

        // 2. Initialize the HomeViewModel with our real store
        val viewModel = HomeViewModel()
        viewModel.initialize(apiKeyStore, repository, store)

        // Wait for the ViewModel to load conversation A (which has 1 message)
        waitForViewModelLoad(viewModel, testScheduler, targetToChangeFrom = null, expectedMessageCount = 1)

        // Verify A was successfully loaded
        assertEquals("Storage error: ${viewModel.storageError.value}", null, viewModel.storageError.value)
        assertEquals(chatA.id, viewModel.activeConversationId.value)
        assertEquals("Hello from conversation A", viewModel.messages.value?.firstOrNull()?.content)

        // Invoke the exact path the New Chat button triggers (clearChat)
        viewModel.clearChat()
        
        // Wait for the new conversation to be created and loaded (which has 0 messages)
        waitForViewModelLoad(viewModel, testScheduler, targetToChangeFrom = chatA.id, expectedMessageCount = 0)

        // Assert (1): A's file contains NO conversationDeleted event
        val workspacesDir = tempDir.resolve("workspaces")
        val workspaceFolders = workspacesDir.listFiles()
        assertTrue("Workspace directory should exist", workspaceFolders != null && workspaceFolders.isNotEmpty())
        val workspaceFolder = workspaceFolders!!.first()
        val fileA = workspaceFolder.listFiles { f -> f.isFile && f.name.contains(chatA.id) }?.firstOrNull()
        assertTrue("Conversation A's file should exist on disk", fileA != null)
        val fileAContents = fileA!!.readText()
        assertFalse("Conversation A's JSONL file must NOT contain a conversationDeleted event", fileAContents.contains("conversationDeleted"))

        // Assert (2): A.deleted == 0 in Room
        val cursor = db.openHelper.readableDatabase.query("SELECT deleted FROM conversations WHERE id = '${chatA.id}'")
        assertTrue("Conversation A should still be in the database", cursor.moveToFirst())
        val deleted = cursor.getInt(0)
        assertEquals("Conversation A's deleted column in Room must be 0", 0, deleted)
        cursor.close()

        // Assert (3): the list Flow emits BOTH A and the new B for W
        val listFlowEmissions = store.observeConversations(ws.id).first()
        assertEquals("Flow should emit exactly 2 conversations (A and B)", 2, listFlowEmissions.size)
        assertTrue("Flow emissions should contain conversation A", listFlowEmissions.any { it.id == chatA.id })

        val uiConversations = viewModel.conversations.value.orEmpty()
        assertEquals("ViewModel conversations LiveData should contain 2 chats", 2, uiConversations.size)
        assertTrue("ViewModel conversations should contain chat A", uiConversations.any { it.id == chatA.id })

        // Assert (4): A still opens and its messages load from its file
        val chatBId = viewModel.activeConversationId.value
        viewModel.selectConversation(chatA.id)
        
        // Wait for switching back to A (which has 1 message)
        waitForViewModelLoad(viewModel, testScheduler, targetToChangeFrom = chatBId, expectedMessageCount = 1)
        
        assertEquals("Conversation A must successfully reopen", chatA.id, viewModel.activeConversationId.value)
        assertEquals("Conversation A's messages must load successfully from its file", "Hello from conversation A", viewModel.messages.value?.firstOrNull()?.content)

        db.close()
    }
}
