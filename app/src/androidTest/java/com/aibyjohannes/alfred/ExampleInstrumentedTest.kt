package com.aibyjohannes.alfred

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationIndexDatabase
import com.aibyjohannes.alfred.data.local.FileChatHistoryStorage
import com.aibyjohannes.alfred.data.local.FileConversationStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

/**
 * Instrumented tests for the Alfred app.
 * These tests run on an Android device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class AlfredInstrumentedTest {

    @Test
    fun appPackage_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.aibyjohannes.alfred", appContext.packageName)
    }

    @Test
    fun appContext_isNotNull() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
    }

    @Test
    fun testRebuildAndListFlow() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(appContext.cacheDir, "test_workspace_rebuild_${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        try {
            // 1. Create a store with Context so it uses RoomConversationIndex
            val store = FileConversationStore(FileChatHistoryStorage(testDir), appContext)
            
            // 2. Create first conversation
            val firstChat = store.getOrCreateActiveConversation()
            store.appendMessage(firstChat.id, ChatMessage.ROLE_USER, "Hello, first chat")
            
            // 3. Create second conversation
            val secondChat = store.createConversation()
            store.appendMessage(secondChat.id, ChatMessage.ROLE_USER, "Hello, second chat")
            
            // Confirm we have two files in the workspace directory
            val workspaceFolders = testDir.listFiles { f -> f.isDirectory && f.name.startsWith("workspace-") }
            assertNotNull(workspaceFolders)
            val workspaceFolder = workspaceFolders!!.first()
            val files = workspaceFolder.listFiles { f -> f.isFile && f.name.startsWith("conversation-") }
            assertNotNull(files)
            assertEquals(2, files!!.size)
            
            // Rebuild Room database from the files
            store.rebuildIndexFromFiles()
            
            // Retrieve active workspace ID
            val workspaces = store.listWorkspaces()
            val activeWorkspaceId = workspaces.first().id
            
            // Get Flow of conversations for this workspace
            val flowEmissions = store.observeConversations(activeWorkspaceId).first()
            
            // Log for diagnostic purposes
            android.util.Log.d("TEST_REBUILD", "Flow emissions count: ${flowEmissions.size}")
            flowEmissions.forEach {
                android.util.Log.d("TEST_REBUILD", " - Chat ID: ${it.id}, title: ${it.title}")
            }
            
            // Query Room directly
            val db = ConversationIndexDatabase.get(appContext)
            val dbRows = db.dao().observeConversations(activeWorkspaceId).first()
            android.util.Log.d("TEST_REBUILD", "Room db query count: ${dbRows.size}")
            
            // Assert that BOTH are present in the Flow emission and Room database
            assertEquals("Flow should emit 2 conversations", 2, flowEmissions.size)
            assertEquals("Room database should contain 2 rows", 2, dbRows.size)
            
            // Switch active conversation and assert list size does not change
            store.switchActiveConversation(firstChat.id)
            val flowEmissionsAfterSwitch = store.observeConversations(activeWorkspaceId).first()
            assertEquals("Flow should still emit 2 conversations after switching active ID", 2, flowEmissionsAfterSwitch.size)
            
        } finally {
            testDir.deleteRecursively()
        }
    }
}