package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileConversationStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create append load list switch and delete conversations`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "Remember that I prefer Kotlin.")
        store.appendMessage(first.id, ChatMessage.ROLE_ASSISTANT, "Understood.")

        val second = store.createConversation()
        assertNotEquals(first.id, second.id)
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "This is about Android.")

        val conversations = store.listConversations()
        assertEquals(2, conversations.size)
        assertEquals(second.id, conversations.first().id)

        val firstMessages = store.loadMessages(first.id)
        assertEquals(2, firstMessages.size)
        assertEquals("Remember that I prefer Kotlin.", firstMessages.first().content)

        val selected = store.switchActiveConversation(first.id)
        assertEquals(first.id, selected.id)

        store.deleteConversation(first.id)
        assertEquals(1, store.listConversations().size)
        assertTrue(store.loadMessages(first.id).isEmpty())
    }

    @Test
    fun `searchSessionMessages returns scored limited matches`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())
        val first = store.getOrCreateActiveConversation()
        store.appendMessage(first.id, ChatMessage.ROLE_USER, "Kotlin coroutines are useful for Android work.")
        store.appendMessage(first.id, ChatMessage.ROLE_ASSISTANT, "Kotlin can keep async code tidy.")

        val second = store.createConversation()
        store.appendMessage(second.id, ChatMessage.ROLE_USER, "Groceries and calendar reminders.")

        val hits = store.searchSessionMessages("Kotlin Android", limit = 1)

        assertEquals(1, hits.size)
        assertEquals(first.id, hits.single().conversationId)
        assertTrue(hits.single().snippet.contains("Kotlin"))
    }

    @Test
    fun `stores chat history in stable workspace jsonl files`() = runTest {
        val root = temporaryFolder.newFolder("Alfred")
        val store = FileConversationStore(root)

        val personal = store.getOrCreateActiveConversation()
        store.appendMessage(personal.id, ChatMessage.ROLE_USER, "Hello from personal.")

        val work = store.createWorkspace("Work Stuff")
        val workConversation = store.getOrCreateActiveConversation()
        store.appendMessage(workConversation.id, ChatMessage.ROLE_USER, "Hello from work.")
        store.renameWorkspace(work.id, "Renamed Work")

        assertTrue(root.resolve("metadata.json").exists())
        assertTrue(root.resolve("workspace-1-personal/conversation-${personal.id}.jsonl").exists())
        assertTrue(root.resolve("workspace-${work.id}-work-stuff/conversation-${workConversation.id}.jsonl").exists())
        assertFalse(root.resolve("workspace-${work.id}-renamed-work").exists())
    }

    @Test
    fun `active conversation is restored per workspace`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val personalFirst = store.getOrCreateActiveConversation()
        store.appendMessage(personalFirst.id, ChatMessage.ROLE_USER, "First personal chat.")
        val personalSecond = store.createConversation()
        store.appendMessage(personalSecond.id, ChatMessage.ROLE_USER, "Second personal chat.")
        store.switchActiveConversation(personalFirst.id)

        val work = store.createWorkspace("Work")
        val workConversation = store.getOrCreateActiveConversation()
        store.appendMessage(workConversation.id, ChatMessage.ROLE_USER, "Work chat.")

        store.switchActiveWorkspace(1L)
        assertEquals(personalFirst.id, store.getOrCreateActiveConversation().id)

        store.switchActiveWorkspace(work.id)
        assertEquals(workConversation.id, store.getOrCreateActiveConversation().id)
    }

    @Test
    fun `switchActiveConversation rejects conversations from other workspaces`() = runTest {
        val store = FileConversationStore(temporaryFolder.newFolder())

        val personal = store.getOrCreateActiveConversation()
        store.appendMessage(personal.id, ChatMessage.ROLE_USER, "Personal chat.")

        store.createWorkspace("Work")
        val work = store.getOrCreateActiveConversation()

        val result = runCatching {
            store.switchActiveConversation(personal.id)
        }

        assertTrue(result.isFailure)
        assertEquals(work.id, store.getOrCreateActiveConversation().id)
    }

    @Test
    fun `deleteConversation removes jsonl file`() = runTest {
        val root = temporaryFolder.newFolder()
        val store = FileConversationStore(root)

        val conversation = store.getOrCreateActiveConversation()
        store.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Temporary chat.")
        val jsonl = root.resolve("workspace-1-personal/conversation-${conversation.id}.jsonl")
        assertTrue(jsonl.exists())

        store.deleteConversation(conversation.id)

        assertFalse(jsonl.exists())
        assertTrue(store.loadMessages(conversation.id).isEmpty())
    }
}
