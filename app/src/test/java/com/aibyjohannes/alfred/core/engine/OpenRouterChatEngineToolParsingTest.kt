package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterChatEngineToolParsingTest {
    @Test
    fun `local knowledge tool name is exposed`() {
        assertEquals("SearchLocalKnowledgeTool", OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME)
    }

    @Test
    fun `local knowledge tool arguments are parsed with defaults and caps`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "extractLocalKnowledgeSearchRequest",
            String::class.java
        )
        method.isAccessible = true

        val request = method.invoke(
            engine,
            """{"query":"Kotlin preference","limit":99,"source":"sessions"}"""
        ) as LocalKnowledgeSearchRequest

        assertEquals("Kotlin preference", request.query)
        assertEquals(10, request.limit)
        assertEquals(LocalKnowledgeSource.SESSIONS, request.source)
    }

    @Test
    fun `local knowledge tool argument parsing rejects missing query`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "extractLocalKnowledgeSearchRequest",
            String::class.java
        )
        method.isAccessible = true

        val request = method.invoke(engine, """{"limit":3}""")

        assertNull(request)
    }

    @Test
    fun `local knowledge formatter reports empty matches`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "formatLocalKnowledgeResults",
            List::class.java
        )
        method.isAccessible = true

        val output = method.invoke(engine, emptyList<LocalKnowledgeSearchResult>()) as String

        assertTrue(output.contains("No local sessions or memories matched"))
    }

    private fun buildEngine(): OpenRouterChatEngine {
        return OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = object : WebSearchClient {
                override suspend fun search(query: String): Result<String> = Result.success("")
            },
            localKnowledgeSearchClient = object : LocalKnowledgeSearchClient {
                override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> {
                    return Result.success(emptyList())
                }
            }
        )
    }
}
