package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
