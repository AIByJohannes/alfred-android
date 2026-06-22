package com.aibyjohannes.alfred.notifications

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationStore
import com.aibyjohannes.alfred.data.local.ConversationMessageDraft
import com.aibyjohannes.alfred.data.local.ConversationSummary
import com.aibyjohannes.alfred.data.local.StoredChatMessage
import com.aibyjohannes.alfred.data.local.WorkspaceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPersonalizerTest {
    @Test
    fun `uses recent safe conversation title`() = runTest {
        val store = FakeConversationStore(
            conversations = listOf(
                ConversationSummary("1", "Android app icon", 2_000L),
                ConversationSummary("2", "Older topic", 1_000L)
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
                ConversationSummary("1", "api key sk-abc12345678901234567890", 3_000L),
                ConversationSummary("2", "https://example.com/only", 2_000L),
                ConversationSummary("3", "Kotlin notifications", 1_000L)
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
            conversations = listOf(ConversationSummary("1", null, 1_000L)),
            messages = mapOf(
                "1" to listOf(
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
        private val messages: Map<String, List<StoredChatMessage>> = emptyMap()
    ) : ConversationStore {
        override suspend fun listWorkspaces(): List<WorkspaceSummary> = listOf(WorkspaceSummary("1", "Personal"))
        override fun observeWorkspaces(): Flow<List<WorkspaceSummary>> = flowOf(listOf(WorkspaceSummary("1", "Personal")))
        override suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary = WorkspaceSummary("1", "Personal")
        override suspend fun createWorkspace(name: String): WorkspaceSummary = WorkspaceSummary("2", name)
        override suspend fun switchActiveWorkspace(workspaceId: String): WorkspaceSummary = WorkspaceSummary(workspaceId, "Personal")
        override suspend fun renameWorkspace(workspaceId: String, newName: String) = Unit
        override suspend fun deleteWorkspace(workspaceId: String) = Unit
        override suspend fun getOrCreateActiveConversation(): ConversationSummary = conversations.first()
        override suspend fun listConversations(): List<ConversationSummary> = conversations
        override fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>> = flowOf(conversations)
        override suspend fun createConversation(): ConversationSummary = ConversationSummary("99", null, 0L)
        override suspend fun switchActiveConversation(conversationId: String): ConversationSummary {
            return conversations.first { it.id == conversationId }
        }
        override suspend fun loadMessages(conversationId: String): List<StoredChatMessage> {
            return messages[conversationId].orEmpty()
        }
        override suspend fun appendMessage(conversationId: String, role: String, content: String) = Unit
        override suspend fun appendMessages(conversationId: String, messages: List<ConversationMessageDraft>) = Unit
        override suspend fun deleteConversation(conversationId: String) = Unit
    }
}
