package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.data.api.ChatMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test
    fun `search handles memory record with missing title and epoch fallbacks`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        
        // Memory record with null title and updatedAtEpochMs = 0 (falls back to createdAtEpochMs = 123)
        memoryFile.writeText(
            """{"id":"m2","title":null,"content":"Test content for epoch check.","createdAtEpochMs":123,"updatedAtEpochMs":0}""" + "\n"
        )

        val client = FileLocalKnowledgeSearchClient(
            conversationStore = FileConversationStore(root),
            memorySearchSource = FileMemorySearchSource(memoryFile)
        )

        val results = client.search(LocalKnowledgeSearchRequest(query = "epoch", limit = 5)).getOrThrow()
        assertEquals(1, results.size)
        
        val hit = results.single()
        assertEquals("Memory", hit.title)
        assertEquals(123L, hit.timestampEpochMs)
    }

    @Test
    fun `search handles memory record with blank title`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        
        // Memory record with blank title
        memoryFile.writeText(
            """{"id":"m3","title":"   ","content":"Some content for test.","createdAtEpochMs":100,"updatedAtEpochMs":200}""" + "\n"
        )

        val client = FileLocalKnowledgeSearchClient(
            conversationStore = FileConversationStore(root),
            memorySearchSource = FileMemorySearchSource(memoryFile)
        )

        val results = client.search(LocalKnowledgeSearchRequest(query = "content", limit = 5)).getOrThrow()
        assertEquals(1, results.size)
        assertEquals("Memory", results.single().title)
        assertEquals(200L, results.single().timestampEpochMs)
    }

    @Test
    fun `memory search normalizeTerms removes short and duplicate words`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        
        // Normalizes to: "kotlin" (since "a" and "is" are < 2 chars, "kotlin" repeated)
        memoryFile.writeText(
            """{"id":"m4","title":"Kotlin","content":"Kotlin Kotlin Kotlin is a language.","createdAtEpochMs":10,"updatedAtEpochMs":20}""" + "\n"
        )

        val source = FileMemorySearchSource(memoryFile)
        val hits = source.search("a is Kotlin Kotlin", limit = 5)
        assertEquals(1, hits.size)
        assertEquals("Kotlin", hits.single().title)
    }

    @Test
    fun `memory search score count instances correctly`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        memoryFile.writeText(
            """{"id":"m5","title":"Memory 5","content":"word word word","createdAtEpochMs":10,"updatedAtEpochMs":20}""" + "\n"
        )

        val source = FileMemorySearchSource(memoryFile)
        val hits = source.search("word", limit = 5)
        assertEquals(1, hits.size)
        // normalized text matches "word" 3 times
        assertEquals(3, hits.single().score)
    }

    @Test
    fun `memory search buildSnippet prefix and suffix boundary checks`() = runTest {
        val root = temporaryFolder.newFolder()
        val memoryFile = root.resolve("memories.jsonl")
        
        // Make a very long string where the query term is in the middle
        val prefix = "a ".repeat(100)
        val suffix = " b".repeat(300)
        val content = "${prefix}targetword${suffix}"
        
        memoryFile.writeText(
            """{"id":"m6","title":"Memory 6","content":"$content","createdAtEpochMs":10,"updatedAtEpochMs":20}""" + "\n"
        )

        val source = FileMemorySearchSource(memoryFile)
        val hits = source.search("targetword", limit = 5)
        assertEquals(1, hits.size)
        
        val snippet = hits.single().snippet
        assertTrue(snippet.startsWith("..."))
        assertTrue(snippet.endsWith("..."))
        assertTrue(snippet.contains("targetword"))
    }

    @Test
    fun `search returns failure on exception`() = runTest {
        val badStore = mockk<FileConversationStore>()
        coEvery { badStore.searchSessionMessages(any(), any()) } throws RuntimeException("Storage failure")

        val client = FileLocalKnowledgeSearchClient(
            conversationStore = badStore,
            memorySearchSource = FileMemorySearchSource(File("dummy"))
        )

        val result = client.search(LocalKnowledgeSearchRequest(query = "test", source = LocalKnowledgeSource.SESSIONS))
        assertTrue(result.isFailure)
        assertEquals("Storage failure", result.exceptionOrNull()?.message)
    }
}
