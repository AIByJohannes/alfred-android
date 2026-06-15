package com.aibyjohannes.alfred.notifications

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationMessageDraft
import com.aibyjohannes.alfred.data.local.ConversationSummary
import com.aibyjohannes.alfred.data.local.StoredChatMessage
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPersonalizerTest {
    @Test
    fun `uses recent safe conversation title`() = runTest {
        val store = FakeConversationStore(
            conversations = listOf(
                ConversationSummary(1L, "Android app icon", 2_000L),
                ConversationSummary(2L, "Older topic", 1_000L)
            )
        )

        val prompt = NotificationPersonalizer(store).buildPrompt(NotificationKind.DAILY)

        assertTrue(prompt.contains("Android app icon"))
    }

    @Test
    fun `falls back when no recent topic exists`() = runTest {
        val store = FakeConversationStore(conversations = emptyList())

        val prompt = NotificationPersonalizer(store).buildPrompt(NotificationKind.INACTIVITY)

        assertTrue(prompt.isNotBlank())
    }

    @Test
    fun `filters urls and secret looking topics`() = runTest {
        val store = FakeConversationStore(
            conversations = listOf(
                ConversationSummary(1L, "api key sk-abc12345678901234567890", 3_000L),
                ConversationSummary(2L, "https://example.com/only", 2_000L),
                ConversationSummary(3L, "Kotlin notifications", 1_000L)
            )
        )

        val prompt = NotificationPersonalizer(store).buildPrompt(NotificationKind.DAILY)

        assertFalse(prompt.contains("sk-"))
        assertFalse(prompt.contains("example.com"))
        assertTrue(prompt.contains("Kotlin notifications"))
    }

    @Test
    fun `uses recent user message when title is unavailable`() = runTest {
        val store = FakeConversationStore(
            conversations = listOf(ConversationSummary(1L, null, 1_000L)),
            messages = mapOf(
                1L to listOf(
                    StoredChatMessage(1L, ChatMessage.ROLE_ASSISTANT, "Sure."),
                    StoredChatMessage(2L, ChatMessage.ROLE_USER, "Improve notification scheduling")
                )
            )
        )

        val prompt = NotificationPersonalizer(store).buildPrompt(NotificationKind.INACTIVITY)

        assertTrue(prompt.contains("Improve notification scheduling"))
    }

    private class FakeConversationStore(
        private val conversations: List<ConversationSummary>,
        private val messages: Map<Long, List<StoredChatMessage>> = emptyMap()
    ) : ConversationStore {
        override suspend fun listWorkspaces(): List<WorkspaceSummary> = listOf(WorkspaceSummary(1L, "Personal"))
        override suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary = WorkspaceSummary(1L, "Personal")
        override suspend fun createWorkspace(name: String): WorkspaceSummary = WorkspaceSummary(2L, name)
        override suspend fun switchActiveWorkspace(workspaceId: Long): WorkspaceSummary = WorkspaceSummary(workspaceId, "Personal")
        override suspend fun renameWorkspace(workspaceId: Long, newName: String) = Unit
        override suspend fun deleteWorkspace(workspaceId: Long) = Unit
        override suspend fun getOrCreateActiveConversation(): ConversationSummary = conversations.first()
        override suspend fun listConversations(): List<ConversationSummary> = conversations
        override suspend fun createConversation(): ConversationSummary = ConversationSummary(99L, null, 0L)
        override suspend fun switchActiveConversation(conversationId: Long): ConversationSummary {
            return conversations.first { it.id == conversationId }
        }
        override suspend fun loadMessages(conversationId: Long): List<StoredChatMessage> {
            return messages[conversationId].orEmpty()
        }
        override suspend fun appendMessage(conversationId: Long, role: String, content: String) = Unit
        override suspend fun appendMessages(conversationId: Long, messages: List<ConversationMessageDraft>) = Unit
        override suspend fun deleteConversation(conversationId: Long) = Unit
    }
}
