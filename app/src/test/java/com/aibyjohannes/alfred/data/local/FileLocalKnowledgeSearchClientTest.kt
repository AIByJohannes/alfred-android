package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.data.api.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLocalKnowledgeSearchClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `search combines sessions and memories with source filters and limits`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        memoryFile.writeText(
            """{"id":"m1","title":"Language preference","content":"The user likes Kotlin examples.","createdAtEpochMs":10,"updatedAtEpochMs":30}""" + "\n"
        )
        val conversationStore = FileConversationStore(root)
        val conversation = conversationStore.getOrCreateActiveConversation()
        conversationStore.appendMessage(conversation.id, ChatMessage.ROLE_USER, "Kotlin Android sessions should be searchable.")

        val client = FileLocalKnowledgeSearchClient(
            conversationStore = conversationStore,
            memorySearchSource = FileMemorySearchSource(memoryFile)
        )

        val all = client.search(LocalKnowledgeSearchRequest(query = "Kotlin", limit = 10)).getOrThrow()
        assertEquals(2, all.size)

        val memories = client.search(
            LocalKnowledgeSearchRequest(
                query = "Kotlin",
                limit = 10,
                source = LocalKnowledgeSource.MEMORIES
            )
        ).getOrThrow()
        assertEquals(1, memories.size)
        assertEquals(LocalKnowledgeSource.MEMORIES, memories.single().source)

        val sessions = client.search(
            LocalKnowledgeSearchRequest(
                query = "Kotlin",
                limit = 1,
                source = LocalKnowledgeSource.SESSIONS
            )
        ).getOrThrow()
        assertEquals(1, sessions.size)
        assertEquals(LocalKnowledgeSource.SESSIONS, sessions.single().source)
    }

    @Test
    fun `search returns empty results for no matches`() = runTest {
        val root = temporaryFolder.newFolder()
        val client = FileLocalKnowledgeSearchClient(
            conversationStore = FileConversationStore(root),
            memorySearchSource = FileMemorySearchSource(root.resolve("memories.jsonl"))
        )

        val results = client.search(LocalKnowledgeSearchRequest(query = "unmatched")).getOrThrow()

        assertTrue(results.isEmpty())
    }
}
