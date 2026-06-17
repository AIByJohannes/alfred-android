package com.aibyjohannes.alfred.core.engine

import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.streaming.StreamFrame
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
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
                OpenRouterChatEngine.TICKTICK_FUNCTION_NAME,
                OpenRouterChatEngine.ASK_SMART_MODEL_FUNCTION_NAME
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
    fun `reasoning merge keeps streamed text when completion is partial`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("preferFullerReasoning")
        }
        method.isAccessible = true

        val output = method.invoke(
            buildEngine(),
            listOf("this."),
            "Thinking about this."
        ) as List<*>

        assertEquals(listOf("Thinking about this."), output)
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

    @Test
    fun `ticktick tool failures are classified as errors`() = runTest {
        val engine = buildEngine()
        val method = OpenRouterChatEngine::class.java.declaredMethods.first { it.name.startsWith("executeToolCall") }
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<String> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<String>) {}
        }

        val result1 = method.invoke(
            engine,
            OpenRouterChatEngine.TICKTICK_FUNCTION_NAME,
            """{"action":"list_tasks"}""",
            continuation
        ) as String

        assertTrue(result1.contains("TickTick integration is not configured"))

        fun checkIsError(result: String): Boolean {
            return result.startsWith("Web search failed", ignoreCase = true) ||
                result.startsWith("Local knowledge search failed", ignoreCase = true) ||
                result.startsWith("Unknown tool", ignoreCase = true) ||
                result.startsWith("TickTick failed", ignoreCase = true) ||
                result.startsWith("TickTick integration is not configured", ignoreCase = true) ||
                result.startsWith("Error:", ignoreCase = true) ||
                result.startsWith("Unknown TickTick action", ignoreCase = true) ||
                result.startsWith("Smart model delegation failed", ignoreCase = true)
        }

        assertTrue(checkIsError(result1))
        assertTrue(checkIsError("TickTick failed: connection timeout"))
        assertTrue(checkIsError("Error: 'projectId' and 'taskId' are required for get_task"))
        assertTrue(checkIsError("Unknown TickTick action: do_something"))
        assertTrue(checkIsError("Smart model delegation failed: API error"))
    }

    @Test
    fun `streamSingleCompletion deduplicates multiple ReasoningComplete frames`() = runTest {
        val client = mockk<OpenRouterLLMClient>(relaxed = true)
        val engine = spyk(buildEngine()) {
            every { createOpenRouterClient() } returns client
        }

        coEvery { client.executeStreaming(any(), any(), any()) } returns flowOf(
            StreamFrame.ReasoningDelta(text = "Thinking...", summary = null),
            StreamFrame.ReasoningComplete(id = "reasoning-0", content = listOf("Thinking..."), summary = emptyList(), encrypted = null),
            StreamFrame.ReasoningComplete(id = "reasoning-0", content = listOf("Thinking... More thinking..."), summary = emptyList(), encrypted = null),
            StreamFrame.TextDelta(text = "Final answer")
        )

        val events = mutableListOf<ChatStreamEvent>()
        engine.streamMessage("hello", emptyList()).toList(events)

        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().firstOrNull()
        requireNotNull(completed)

        val intermediateMessages = completed.result.intermediateMessages
        val reasoningMessages = intermediateMessages.filter { it.kind == CoreChatMessageKind.REASONING }

        assertEquals("Should only have one reasoning message due to deduplication", 1, reasoningMessages.size)
        assertEquals("Thinking... More thinking...", reasoningMessages.first().content)
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

    @Test
    fun `smart model delegation tool arguments are parsed correctly`() {
        val details = extractSmartModelDelegationDetails(
            buildEngine(),
            """{"task_details":"Write a plan to fix build","context":"Use gradle wrapper"}"""
        )

        requireNotNull(details)
        assertEquals("Write a plan to fix build", details.taskDetails)
        assertEquals("Use gradle wrapper", details.context)
    }

    @Test
    fun `smart model delegation tool arguments rejection on missing task_details`() {
        val details = extractSmartModelDelegationDetails(
            buildEngine(),
            """{"context":"Only context is provided"}"""
        )

        assertNull(details)
    }

    private fun extractSmartModelDelegationDetails(
        engine: OpenRouterChatEngine,
        argumentsJson: String
    ): OpenRouterChatEngine.SmartModelDelegationDetails? {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractSmartModelDelegationDetails")
        }
        method.isAccessible = true
        return method.invoke(engine, argumentsJson) as OpenRouterChatEngine.SmartModelDelegationDetails?
    }
}
