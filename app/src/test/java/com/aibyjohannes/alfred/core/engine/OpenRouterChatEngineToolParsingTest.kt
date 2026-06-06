package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional

class OpenRouterChatEngineToolParsingTest {
    @Test
    fun `local knowledge tool name is exposed`() {
        assertEquals("SearchLocalKnowledgeTool", OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME)
    }

    @Test
    fun `initial request params include both Alfred tools`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "buildInitialParams",
            List::class.java
        )
        method.isAccessible = true

        val params = method.invoke(engine, buildMessages()) as ChatCompletionCreateParams
        val toolNames = params.tools().get().map { it.asFunction().function().name() }

        assertEquals(
            listOf(
                OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME
            ),
            toolNames
        )
    }

    @Test
    fun `tool follow up request builder includes both Alfred tools`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "buildToolFollowUpBuilder",
            List::class.java,
            ChatCompletionMessage::class.java
        )
        method.isAccessible = true

        val builder = method.invoke(engine, buildMessages(), buildAssistantMessage()) as ChatCompletionCreateParams.Builder
        val params = builder.build()
        val toolNames = params.tools().get().map { it.asFunction().function().name() }

        assertEquals(
            listOf(
                OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME
            ),
            toolNames
        )
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

    @Test
    fun `web search argument parsing rejects missing query`() {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.getDeclaredMethod(
            "extractWebSearchQuery",
            String::class.java
        )
        method.isAccessible = true

        val output = method.invoke(engine, """{"not_query":"latest news"}""")

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

    private fun buildMessages(): List<ChatCompletionMessageParam> {
        return listOf(
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content("Search for current Kotlin news")
                    .build()
            )
        )
    }

    private fun buildAssistantMessage(): ChatCompletionMessage {
        return ChatCompletionMessage.builder()
            .role(JsonValue.from("assistant"))
            .content("")
            .refusal(Optional.empty())
            .build()
    }
}
