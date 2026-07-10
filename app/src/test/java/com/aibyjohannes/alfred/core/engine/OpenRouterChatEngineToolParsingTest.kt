package com.aibyjohannes.alfred.core.engine

import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.core.reminders.ReminderClient
import com.aibyjohannes.alfred.core.reminders.ReminderRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.aibyjohannes.alfred.core.skills.SkillClient
import com.aibyjohannes.alfred.core.skills.SkillSummary
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class OpenRouterChatEngineToolParsingTest {
    @Test
    fun `result envelopes are normalized before tool argument parsing`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())

        assertEquals("{\"query\":\"weather\"}", engine.normalizeToolArguments("{\"result\":{\"query\":\"weather\"}}"))
        assertEquals("{\"path\":\"Daily.md\"}", engine.normalizeToolArguments("{\"result\":\"{\\\"path\\\":\\\"Daily.md\\\"}\"}"))
    }
    @Test
    fun `efficiency and privacy settings add OpenRouter routing preferences`() {
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            efficiencyModeEnabled = true,
            privacyModeEnabled = true,
            sessionId = "local-conversation-id"
        )

        val properties = engine.requestAdditionalProperties(reasoningEnabled = true)

        assertEquals(JsonPrimitive(true), (properties["provider"] as JsonObject)["zdr"])
        assertTrue((properties["session_id"] as JsonPrimitive).content.startsWith("alfred-"))
        assertTrue("local-conversation-id" !in (properties["session_id"] as JsonPrimitive).content)
        assertTrue(properties.containsKey("reasoning"))
    }

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
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("buildAlfredToolDescriptors") &&
            it.parameterCount == 1 &&
            it.parameterTypes[0] == java.util.List::class.java
        }
        method.isAccessible = true
        val mockMcpTool = ai.koog.agents.core.tools.ToolDescriptor(
            name = "mcp_tool_name",
            description = "mcp tool desc",
            requiredParameters = emptyList()
        )
        val tools = method.invoke(buildEngine(), listOf(mockMcpTool)) as List<*>

        assertEquals(
            listOf(
                OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME,
                OpenRouterChatEngine.ASK_SMART_MODEL_FUNCTION_NAME,
                OpenRouterChatEngine.SCHEDULE_REMINDER_TOOL,
                "mcp_tool_name"
            ),
            tools.map { it?.javaClass?.getMethod("getName")?.invoke(it) }
        )
    }

    @Test
    fun `reminder tool schedules a future dated notification`() = runTest {
        val reminderClient = mockk<ReminderClient>()
        val scheduledAt = OffsetDateTime.now().plusMinutes(5).withNano(0)
        coEvery { reminderClient.scheduleReminder(any()) } returns Result.success("Reminder scheduled")
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            reminderClient = reminderClient
        )

        val result = executeToolCall(
            engine,
            OpenRouterChatEngine.SCHEDULE_REMINDER_TOOL,
            """{"message":"Stretch","scheduled_at":"$scheduledAt"}"""
        )

        assertEquals("Reminder scheduled", result)
        coVerify {
            reminderClient.scheduleReminder(
                ReminderRequest("Stretch", scheduledAt.toInstant().toEpochMilli())
            )
        }
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
                result.startsWith("Smart model delegation failed", ignoreCase = true) ||
                result.startsWith("Obsidian create failed", ignoreCase = true) ||
                result.startsWith("Obsidian update failed", ignoreCase = true) ||
                result.startsWith("Obsidian rename failed", ignoreCase = true) ||
                result.startsWith("Obsidian delete failed", ignoreCase = true)
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

    @Test
    fun `sendMessage succeeds and returns chat turn result`() = runTest {
        val client = mockk<OpenRouterLLMClient>(relaxed = true)
        val engine = spyk(buildEngine()) {
            every { createOpenRouterClient() } returns client
        }

        coEvery { client.executeStreaming(any(), any(), any()) } returns flowOf(
            StreamFrame.TextDelta(text = "Final answer")
        )

        val result = engine.sendMessage("hello", emptyList())
        assertTrue(result.isSuccess)
        assertEquals("Final answer", result.getOrThrow().content)
    }

    @Test
    fun `sendMessage returns failure on exception`() = runTest {
        val engine = spyk(buildEngine()) {
            every { createOpenRouterClient() } throws RuntimeException("Connection error")
        }

        val result = engine.sendMessage("hello", emptyList())
        assertTrue(result.isFailure)
        assertEquals("Connection error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sendMessage returns failure when no Completed event emitted`() = runTest {
        val client = mockk<OpenRouterLLMClient>(relaxed = true)
        val engine = spyk(buildEngine()) {
            every { createOpenRouterClient() } returns client
        }

        coEvery { client.executeStreaming(any(), any(), any()) } returns flowOf(
            // empty flow
        )

        val result = engine.sendMessage("hello", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No response content from AI") == true)
    }

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    private suspend fun executeToolCall(
        engine: OpenRouterChatEngine,
        functionName: String,
        arguments: String
    ): String = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("executeToolCall")
        }
        method.isAccessible = true
        method.invoke(engine, functionName, arguments, cont)
    }

    @Test
    fun `executeToolCall web_search success`() = runTest {
        val webSearch = mockk<WebSearchClient>()
        coEvery { webSearch.search("queryText") } returns Result.success("Search hits found")
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = webSearch
        )
        val result = executeToolCall(engine, OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME, """{"query":"queryText"}""")
        assertEquals("Search hits found", result)
    }

    @Test
    fun `executeToolCall web_search failure`() = runTest {
        val webSearch = mockk<WebSearchClient>()
        coEvery { webSearch.search("queryText") } returns Result.failure(Exception("network error"))
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = webSearch
        )
        val result = executeToolCall(engine, OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME, """{"query":"queryText"}""")
        assertEquals("Web search failed: network error", result)
    }

    @Test
    fun `executeToolCall web_search empty query`() = runTest {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())
        val result = executeToolCall(engine, OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME, """{"query":""}""")
        assertEquals("Web search failed: missing required 'query' argument.", result)
    }

    @Test
    fun `executeToolCall local_knowledge_search success`() = runTest {
        val lkSearch = mockk<LocalKnowledgeSearchClient>()
        coEvery { lkSearch.search(any()) } returns Result.success(
            listOf(
                LocalKnowledgeSearchResult(
                    source = LocalKnowledgeSource.MEMORIES,
                    title = "Mem1",
                    snippet = "some snippet",
                    timestampEpochMs = 123456L
                )
            )
        )
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            localKnowledgeSearchClient = lkSearch
        )
        val result = executeToolCall(engine, OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME, """{"query":"Kotlin"}""")
        assertTrue(result.contains("Mem1"))
        assertTrue(result.contains("some snippet"))
    }

    @Test
    fun `executeToolCall local_knowledge_search failure`() = runTest {
        val lkSearch = mockk<LocalKnowledgeSearchClient>()
        coEvery { lkSearch.search(any()) } returns Result.failure(Exception("disk error"))
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            localKnowledgeSearchClient = lkSearch
        )
        val result = executeToolCall(engine, OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME, """{"query":"Kotlin"}""")
        assertEquals("Local knowledge search failed: disk error", result)
    }

    @Test
    fun `executeToolCall local_knowledge_search invalid query`() = runTest {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())
        val result = executeToolCall(engine, OpenRouterChatEngine.LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME, """{"limit":5}""")
        assertEquals("Local knowledge search failed: missing required 'query' argument.", result)
    }

    @Test
    fun `executeToolCall ask_smart_model success`() = runTest {
        val client = mockk<OpenRouterLLMClient>()
        val engine = spyk(OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())) {
            every { createOpenRouterClient() } returns client
        }

        coEvery { client.executeStreaming(any(), any(), any()) } returns flowOf(
            StreamFrame.TextDelta(text = "Smart plan content")
        )
        every { client.close() } just Runs

        val result = executeToolCall(engine, OpenRouterChatEngine.ASK_SMART_MODEL_FUNCTION_NAME, """{"task_details":"reason about this"}""")
        assertEquals("Smart plan content", result)
    }

    @Test
    fun `executeToolCall ask_smart_model exception`() = runTest {
        val client = mockk<OpenRouterLLMClient>()
        val engine = spyk(OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())) {
            every { createOpenRouterClient() } returns client
        }

        coEvery { client.executeStreaming(any(), any(), any()) } throws RuntimeException("streaming error")
        every { client.close() } just Runs

        val result = executeToolCall(engine, OpenRouterChatEngine.ASK_SMART_MODEL_FUNCTION_NAME, """{"task_details":"reason about this"}""")
        assertEquals("Smart model delegation failed: streaming error", result)
    }

    @Test
    fun `executeToolCall ask_smart_model missing task_details`() = runTest {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk())
        val result = executeToolCall(engine, OpenRouterChatEngine.ASK_SMART_MODEL_FUNCTION_NAME, """{"context":"context info"}""")
        assertEquals("Smart model delegation failed: missing required 'task_details' argument.", result)
    }

    @Test
    fun `executeToolCall obsidian tools when client null`() = runTest {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockk(), obsidianClient = null)
        
        val searchResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL, """{"query":"test"}""")
        assertEquals("Obsidian integration is not configured.", searchResult)

        val listResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_LIST_FOLDER_TOOL, """{"path":"Daily"}""")
        assertEquals("Obsidian integration is not configured.", listResult)

        val readResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_READ_TOOL, """{"path":"note"}""")
        assertEquals("Obsidian integration is not configured.", readResult)

        val createResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_CREATE_TOOL, """{"path":"note.md","content":"text"}""")
        assertEquals("Obsidian integration is not configured.", createResult)

        val updateResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_UPDATE_TOOL, """{"path":"note.md","content":"text"}""")
        assertEquals("Obsidian integration is not configured.", updateResult)

        val renameResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_RENAME_TOOL, """{"from_path":"note.md","to_path":"renamed.md"}""")
        assertEquals("Obsidian integration is not configured.", renameResult)

        val deleteResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_DELETE_TOOL, """{"path":"note.md"}""")
        assertEquals("Obsidian integration is not configured.", deleteResult)

        val writeResult = executeToolCall(engine, OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL, """{"path":"note","content":"text"}""")
        assertEquals("Obsidian integration is not configured.", writeResult)
    }

    @Test
    fun `skill tools register management always and read tools only when valid skills are available`() {
        val skillClient = mockk<SkillClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            skillClient = skillClient
        )
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("buildAlfredToolDescriptors") && it.parameterCount == 2
        }
        method.isAccessible = true

        val withoutSkills = method.invoke(engine, emptyList<Any>(), false) as List<*>
        val withSkills = method.invoke(engine, emptyList<Any>(), true) as List<*>
        val namesWithout = withoutSkills.map { it?.javaClass?.getMethod("getName")?.invoke(it) }
        val namesWith = withSkills.map { it?.javaClass?.getMethod("getName")?.invoke(it) }

        assertTrue(OpenRouterChatEngine.READ_SKILL_TOOL !in namesWithout)
        assertTrue(OpenRouterChatEngine.CREATE_SKILL_TOOL in namesWithout)
        assertTrue(OpenRouterChatEngine.RENAME_SKILL_TOOL in namesWithout)
        assertTrue(OpenRouterChatEngine.WRITE_SKILL_REFERENCE_TOOL in namesWithout)
        assertTrue(OpenRouterChatEngine.READ_SKILL_TOOL in namesWith)
        assertTrue(OpenRouterChatEngine.READ_SKILL_REFERENCE_TOOL in namesWith)
        assertTrue(OpenRouterChatEngine.CREATE_SKILL_TOOL in namesWith)
        assertTrue(OpenRouterChatEngine.RENAME_SKILL_TOOL in namesWith)
        assertTrue(OpenRouterChatEngine.WRITE_SKILL_REFERENCE_TOOL in namesWith)
    }

    @Test
    fun `skill catalog instructs model to load matching skills on demand`() {
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("formatSkillsCatalog")
        }
        method.isAccessible = true

        val catalog = method.invoke(
            buildEngine(),
            listOf(SkillSummary("meeting-prep", "meeting-prep", "Prepare for meetings"))
        ) as String

        assertTrue(catalog.contains("meeting-prep: Prepare for meetings"))
        assertTrue(catalog.contains(OpenRouterChatEngine.READ_SKILL_TOOL))
        assertTrue(catalog.contains(OpenRouterChatEngine.READ_SKILL_REFERENCE_TOOL))
        assertTrue(catalog.contains(OpenRouterChatEngine.CREATE_SKILL_TOOL))
        assertTrue(catalog.contains(OpenRouterChatEngine.RENAME_SKILL_TOOL))
        assertTrue(catalog.contains(OpenRouterChatEngine.WRITE_SKILL_REFERENCE_TOOL))
    }

    @Test
    fun `stream injects refreshed skill catalog as a system message`() = runTest {
        val skillClient = mockk<SkillClient>()
        coEvery { skillClient.listSkills() } returns Result.success(
            listOf(SkillSummary("meeting-prep", "meeting-prep", "Prepare for meetings"))
        )
        val client = mockk<OpenRouterLLMClient>(relaxed = true)
        val promptSlot = slot<Prompt>()
        coEvery { client.executeStreaming(capture(promptSlot), any(), any()) } returns flowOf(
            StreamFrame.TextDelta(text = "Done")
        )
        val engine = spyk(
            OpenRouterChatEngine(
                apiKey = "test",
                webSearchClient = mockk(),
                skillClient = skillClient
            )
        ) {
            every { createOpenRouterClient() } returns client
        }

        assertTrue(engine.sendMessage("prepare me", emptyList()).isSuccess)

        val messageText = promptSlot.captured.messages.joinToString("\n")
        assertTrue(messageText.contains("meeting-prep: Prepare for meetings"))
        coVerify(exactly = 1) { skillClient.listSkills() }
    }

    @Test
    fun `executeToolCall dispatches skill and reference reads`() = runTest {
        val skillClient = mockk<SkillClient>()
        coEvery { skillClient.readSkill("meeting-prep") } returns Result.success("skill body")
        coEvery {
            skillClient.readReference("meeting-prep", "references/checklist.md")
        } returns Result.success("reference body")
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            skillClient = skillClient
        )

        val skill = executeToolCall(
            engine,
            OpenRouterChatEngine.READ_SKILL_TOOL,
            """{"skill_id":"meeting-prep"}"""
        )
        val reference = executeToolCall(
            engine,
            OpenRouterChatEngine.READ_SKILL_REFERENCE_TOOL,
            """{"skill_id":"meeting-prep","path":"references/checklist.md"}"""
        )

        assertEquals("skill body", skill)
        assertEquals("reference body", reference)
    }

    @Test
    fun `executeToolCall dispatches skill lifecycle operations`() = runTest {
        val skillClient = mockk<SkillClient>()
        coEvery {
            skillClient.createSkill("meeting-prep", "Prepare for meetings", "# Meeting Prep")
        } returns Result.success("created")
        coEvery {
            skillClient.renameSkill("meeting-prep", "meeting-planning")
        } returns Result.success("renamed")
        coEvery {
            skillClient.writeReference("meeting-planning", "references/checklist.md", "Checklist")
        } returns Result.success("wrote")
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            skillClient = skillClient
        )

        val create = executeToolCall(
            engine,
            OpenRouterChatEngine.CREATE_SKILL_TOOL,
            """{"skill_id":"meeting-prep","description":"Prepare for meetings","instructions":"# Meeting Prep"}"""
        )
        val rename = executeToolCall(
            engine,
            OpenRouterChatEngine.RENAME_SKILL_TOOL,
            """{"from_skill_id":"meeting-prep","to_skill_id":"meeting-planning"}"""
        )
        val writeReference = executeToolCall(
            engine,
            OpenRouterChatEngine.WRITE_SKILL_REFERENCE_TOOL,
            """{"skill_id":"meeting-planning","path":"references/checklist.md","content":"Checklist"}"""
        )

        assertEquals("created", create)
        assertEquals("renamed", rename)
        assertEquals("wrote", writeReference)
    }

    @Test
    fun `executeToolCall returns explicit skill argument errors`() = runTest {
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockk(),
            skillClient = mockk()
        )

        assertTrue(
            executeToolCall(engine, OpenRouterChatEngine.READ_SKILL_TOOL, "{}").startsWith("Skill read failed")
        )
        assertTrue(
            executeToolCall(engine, OpenRouterChatEngine.READ_SKILL_REFERENCE_TOOL, "{}").startsWith("Skill reference read failed")
        )
        assertTrue(
            executeToolCall(engine, OpenRouterChatEngine.CREATE_SKILL_TOOL, "{}").startsWith("Skill create failed")
        )
        assertTrue(
            executeToolCall(engine, OpenRouterChatEngine.RENAME_SKILL_TOOL, "{}").startsWith("Skill rename failed")
        )
        assertTrue(
            executeToolCall(engine, OpenRouterChatEngine.WRITE_SKILL_REFERENCE_TOOL, "{}").startsWith("Skill reference write failed")
        )
    }
}

