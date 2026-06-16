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
    fun `koog model preserves selected OpenRouter model id`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first { it.name.startsWith("buildKoogModel") }
        method.isAccessible = true
        val model = method.invoke(buildEngine(), "google/gemini-3.5-flash")
        val provider = model.javaClass.getMethod("getProvider").invoke(model)
        val capabilities = model.javaClass.getMethod("getCapabilities").invoke(model) as List<*>

        assertEquals("OpenRouter", provider.javaClass.getMethod("getDisplay").invoke(provider))
        assertEquals("google/gemini-3.5-flash", model.javaClass.getMethod("getId").invoke(model))
        assertTrue(capabilities.any { it.toString().contains("Tools") })
        assertTrue(capabilities.any { it.toString().contains("Completion") })
    }

    @Test
    fun `openrouter client is created with explicit koog http factory`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first { it.name.startsWith("createOpenRouterClient") }
        method.isAccessible = true

        val client = method.invoke(buildEngine()) as AutoCloseable
        client.close()
    }

    @Test
    fun `tool descriptors include all Alfred tools`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first { it.name.startsWith("buildAlfredToolDescriptors") }
        method.isAccessible = true
        val tools = method.invoke(buildEngine()) as List<*>

        assertEquals(
            listOf(
                OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.TICKTICK_FUNCTION_NAME
            ),
            tools.map { it?.javaClass?.getMethod("getName")?.invoke(it) }
        )
    }

    @Test
    fun `local knowledge tool arguments are parsed with defaults and caps`() {
        val request = extractLocalKnowledgeSearchRequest(
            buildEngine(),
            """{"query":"Kotlin preference","limit":99,"source":"sessions"}"""
        )

        requireNotNull(request)
        assertEquals("Kotlin preference", request.query)
        assertEquals(10, request.limit)
        assertEquals(LocalKnowledgeSource.SESSIONS, request.source)
    }

    @Test
    fun `local knowledge tool argument parsing rejects missing query`() {
        val request = extractLocalKnowledgeSearchRequest(buildEngine(), """{"limit":3}""")

        assertNull(request)
    }

    @Test
    fun `local knowledge formatter reports empty matches`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("formatLocalKnowledgeResults")
        }
        method.isAccessible = true
        val output = method.invoke(buildEngine(), emptyList<LocalKnowledgeSearchResult>()) as String

        assertTrue(output.contains("No local sessions or memories matched"))
    }

    @Test
    fun `web search argument parsing rejects missing query`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractWebSearchQuery")
        }
        method.isAccessible = true
        val output = method.invoke(buildEngine(), """{"not_query":"latest news"}""")

        assertNull(output)
    }

    @Test
    fun `local knowledge tool has empty fallback when client is absent`() = runTest {
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = object : WebSearchClient {
                override suspend fun search(query: String): Result<String> = Result.success("")
            }
        )
        val field = OpenRouterChatEngine::class.java.getDeclaredField("effectiveLocalKnowledgeSearchClient")
        field.isAccessible = true
        val client = field.get(engine) as LocalKnowledgeSearchClient

        val results = client.search(LocalKnowledgeSearchRequest(query = "anything")).getOrThrow()

        assertTrue(results.isEmpty())
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

    private fun extractLocalKnowledgeSearchRequest(
        engine: OpenRouterChatEngine,
        argumentsJson: String
    ): LocalKnowledgeSearchRequest? {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractLocalKnowledgeSearchRequest")
        }
        method.isAccessible = true
        return method.invoke(engine, argumentsJson) as LocalKnowledgeSearchRequest?
    }
}
