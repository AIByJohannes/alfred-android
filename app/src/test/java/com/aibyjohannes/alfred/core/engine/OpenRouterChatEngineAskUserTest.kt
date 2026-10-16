package com.aibyjohannes.alfred.core.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OpenRouterChatEngineAskUserTest {
    private val mapper = ObjectMapper()
    private val arguments = """{"question":"Which project?","options":["Personal","Work"]}"""

    @Test
    fun `waiting suspends the same run until its answer becomes a matching tool result`() = engineTest {
        val entered = CompletableDeferred<AskUserRequest>()
        val answer = CompletableDeferred<AskUserAnswer>()
        val fixture = fixture(AskUserHandler { request ->
            entered.complete(request)
            answer.await()
        })
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val run = launch { fixture.engine.streamMessage("Choose a project", emptyList()).toList(events) }

        assertEquals(AskUserRequest("Which project?", listOf("Personal", "Work")), entered.await())
        assertEquals(1, fixture.invocations.size)
        assertTrue(fixture.invocations.single().prompt.toolResults().isEmpty())
        assertTrue(events.none { it is ChatStreamEvent.ToolResultAvailable || it is ChatStreamEvent.Completed })
        assertFalse(run.isCompleted)

        answer.complete(AskUserAnswer.Option("Work"))
        run.join()

        assertSuccessfulAnswer(fixture, events, mapOf("status" to "answered", "selected_option" to "Work"))
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single().result
        val call = completed.intermediateMessages.single { it.kind == CoreChatMessageKind.TOOL_CALL }
        val result = completed.intermediateMessages.single { it.kind == CoreChatMessageKind.TOOL_RESULT }
        assertEquals("call-question", call.toolCallId)
        assertEquals(OpenRouterChatEngine.ASK_USER_TOOL, call.toolName)
        assertEquals(arguments, call.toolArgumentsJson)
        assertEquals(call.turnId, result.turnId)
        assertTrue(result.includeInPrompt)
        assertFalse(result.isError)
        verify(exactly = 1) { fixture.client.close() }
    }

    @Test
    fun `option custom Unicode text and question cancellation round trip through JSON`() = engineTest {
        val custom = "Use the \"household\" list\nSecond line — åäö 🚀"
        val cases = listOf(
            AskUserAnswer.Option("Work") to mapOf("status" to "answered", "selected_option" to "Work"),
            AskUserAnswer.Text("  $custom  ") to mapOf("status" to "answered", "text" to custom),
            AskUserAnswer.Cancelled to mapOf("status" to "cancelled")
        )
        for ((answer, expected) in cases) {
            val fixture = fixture(AskUserHandler { answer })
            val events = fixture.engine.streamMessage("Ask me", emptyList()).toList()
            assertSuccessfulAnswer(fixture, events, expected)
        }
    }

    @Test
    fun `omitted and empty options support text only answers and normalized envelopes`() = engineTest {
        val inputs = listOf(
            """{"question":"  Tell me more  "}""",
            """{"question":"  Tell me more  ","options":[]}""",
            """{"result":{"question":"  Tell me more  "}}""",
            mapper.writeValueAsString(mapOf("result" to """{"question":"  Tell me more  "}"""))
        )
        for (input in inputs) {
            val requests = CopyOnWriteArrayList<AskUserRequest>()
            val fixture = fixture(AskUserHandler {
                requests += it
                AskUserAnswer.Text("Details")
            }, input)
            val events = fixture.engine.streamMessage("Ask", emptyList()).toList()
            assertEquals(listOf(AskUserRequest("Tell me more")), requests)
            assertSuccessfulAnswer(fixture, events, mapOf("status" to "answered", "text" to "Details"))
        }
    }

    @Test
    fun `question and options are trimmed before displaying or validating an option`() = engineTest {
        val fixture = fixture(AskUserHandler {
            assertEquals(AskUserRequest("Which project?", listOf("Personal", "Work")), it)
            AskUserAnswer.Option("Work")
        }, """{"question":"  Which project?  ","options":[" Personal "," Work "]}""")
        val events = fixture.engine.streamMessage("Ask", emptyList()).toList()
        assertSuccessfulAnswer(fixture, events, mapOf("status" to "answered", "selected_option" to "Work"))
    }

    @Test
    fun `invalid arguments return tool errors without invoking the interaction`() = engineTest {
        val invalidInputs = listOf(
            "{broken", "[]", "null", "42", "\"question\"", "{}",
            """{"question":""}""", """{"question":" \n "}""",
            """{"question":null}""", """{"question":1}""", """{"question":true}""",
            """{"question":[]}""", """{"question":{}}""",
            """{"question":"Q","options":null}""",
            """{"question":"Q","options":"Work"}""",
            """{"question":"Q","options":{}}""",
            """{"question":"Q","options":[1]}""",
            """{"question":"Q","options":[true]}""",
            """{"question":"Q","options":[null]}""",
            """{"question":"Q","options":[{}]}""",
            """{"question":"Q","options":[[]]}""",
            """{"question":"Q","options":[""]}""",
            """{"question":"Q","options":["  "]}""",
            """{"question":"Q","options":["Work","Work"]}""",
            """{"question":"Q","options":[" Work ","Work"]}"""
        )
        for (input in invalidInputs) {
            val entered = AtomicInteger()
            val fixture = fixture(AskUserHandler {
                entered.incrementAndGet()
                AskUserAnswer.Cancelled
            }, input)
            val events = fixture.engine.streamMessage("Ask", emptyList()).toList()
            assertEquals("Unexpected interaction for $input", 0, entered.get())
            assertToolError(fixture, events)
        }
    }

    @Test
    fun `invalid runtime answers are tool errors rather than successful answers`() = engineTest {
        for (answer in listOf(AskUserAnswer.Option("Unknown"), AskUserAnswer.Text(" \n\t "))) {
            val fixture = fixture(AskUserHandler { answer })
            assertToolError(fixture, fixture.engine.streamMessage("Ask", emptyList()).toList())
        }
        val fixture = fixture(AskUserHandler { AskUserAnswer.Option("Work") }, """{"question":"Text only"}""")
        assertToolError(fixture, fixture.engine.streamMessage("Ask", emptyList()).toList())
    }

    @Test
    fun `handler failure becomes an error tool result and permits the next model pass`() = engineTest {
        val fixture = fixture(AskUserHandler { throw IllegalStateException("interaction unavailable") })
        val events = fixture.engine.streamMessage("Ask", emptyList()).toList()
        assertToolError(fixture, events)
        assertTrue(events.filterIsInstance<ChatStreamEvent.ToolResultAvailable>().single().result.contains("interaction unavailable"))
    }

    @Test
    fun `interaction descriptor and instructions are advertised only for an enabled handler`() = engineTest {
        for (hasHandler in listOf(false, true)) {
            for (toolsEnabled in listOf(false, true)) {
                val fixture = fixture(
                    handler = if (hasHandler) AskUserHandler { AskUserAnswer.Cancelled } else null,
                    toolsEnabled = toolsEnabled,
                    firstPass = flowOf(StreamFrame.TextDelta(text = "Done"))
                )
                fixture.engine.streamMessage("Hello", emptyList()).toList()
                val invocation = fixture.invocations.single()
                assertEquals(hasHandler && toolsEnabled, invocation.tools.any { it.name == OpenRouterChatEngine.ASK_USER_TOOL })
                if (!toolsEnabled) assertTrue(invocation.tools.isEmpty())
                val promptText = invocation.prompt.messages.joinToString("\n")
                assertEquals(hasHandler && toolsEnabled, promptText.contains("Never invent an answer."))
                if (hasHandler && toolsEnabled) {
                    val descriptor = invocation.tools.single { it.name == OpenRouterChatEngine.ASK_USER_TOOL }
                    assertEquals(listOf("question"), descriptor.requiredParameters.map { it.name })
                    assertEquals(ToolParameterType.String, descriptor.requiredParameters.single().type)
                    assertEquals(listOf("options"), descriptor.optionalParameters.map { it.name })
                    assertEquals(ToolParameterType.List(ToolParameterType.String), descriptor.optionalParameters.single().type)
                }
            }
        }
    }

    @Test
    fun `unavailable or disabled interaction rejects unsolicited model tool calls`() = engineTest {
        val entered = AtomicInteger()
        val handler = AskUserHandler { entered.incrementAndGet(); AskUserAnswer.Cancelled }
        for (fixture in listOf(fixture(null), fixture(handler, toolsEnabled = false))) {
            assertToolError(fixture, fixture.engine.streamMessage("Ask", emptyList()).toList())
        }
        assertEquals(0, entered.get())
    }

    @Test
    fun `stopping while waiting cancels the waiter and closes the client without resuming`() = engineTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fixture = fixture(AskUserHandler {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        })
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val run = launch { fixture.engine.streamMessage("Ask", emptyList()).toList(events) }
        entered.await()
        run.cancelAndJoin()
        cancelled.await()

        assertTrue(run.isCancelled)
        assertEquals(1, fixture.invocations.size)
        assertTrue(events.none { it is ChatStreamEvent.ToolResultAvailable || it is ChatStreamEvent.Completed })
        verify(exactly = 1) { fixture.client.close() }
    }

    @Test
    fun `sendMessage propagates handler cancellation instead of returning a failed Result`() = engineTest {
        val fixture = fixture(AskUserHandler { throw CancellationException("Stop requested") })
        try {
            fixture.engine.sendMessage("Ask", emptyList())
            fail("Cancellation must propagate rather than becoming Result.failure")
        } catch (expected: CancellationException) {
            assertEquals("Stop requested", expected.message)
        }
        assertEquals(1, fixture.invocations.size)
        verify(exactly = 1) { fixture.client.close() }
    }

    @Test
    fun `model stream cancellation propagates without retrying reasoning fallback`() = engineTest {
        val fixture = fixture(AskUserHandler { AskUserAnswer.Cancelled }, firstPass = flow {
            throw CancellationException("stream stopped")
        })
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        try {
            fixture.engine.streamMessage("Hello", emptyList()).toList(events)
            fail("Stream cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stream stopped", expected.message)
        }
        assertEquals(1, fixture.invocations.size)
        assertTrue(events.none { it is ChatStreamEvent.Completed || it is ChatStreamEvent.ToolResultAvailable })
        verify(exactly = 1) { fixture.client.close() }
    }

    @Test
    fun `multiple tool calls from one pass wait for answers sequentially`() = engineTest {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstAnswer = CompletableDeferred<AskUserAnswer>()
        val secondAnswer = CompletableDeferred<AskUserAnswer>()
        val questions = CopyOnWriteArrayList<String>()
        val fixture = fixture(AskUserHandler { request ->
            questions += request.question
            when (request.question) {
                "First?" -> { firstEntered.complete(Unit); firstAnswer.await() }
                "Second?" -> { secondEntered.complete(Unit); secondAnswer.await() }
                else -> error("Unexpected question: $request")
            }
        }, firstPass = flowOf(
            toolCall("""{"question":"First?"}""", "first", 0),
            toolCall("""{"question":"Second?"}""", "second", 1)
        ))
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val run = async { fixture.engine.streamMessage("Ask twice", emptyList()).toList(events) }
        firstEntered.await()
        assertFalse(secondEntered.isCompleted)
        assertEquals(listOf("First?"), questions)
        assertEquals(1, fixture.invocations.size)
        firstAnswer.complete(AskUserAnswer.Text("One"))
        secondEntered.await()
        assertEquals(listOf("First?", "Second?"), questions)
        assertEquals(1, fixture.invocations.size)
        assertFalse(run.isCompleted)
        secondAnswer.complete(AskUserAnswer.Cancelled)
        run.await()

        assertEquals(2, fixture.invocations.size)
        val results = fixture.invocations.last().prompt.toolResults()
        assertEquals(listOf("first", "second"), results.map { it.id })
        assertEquals(mapper.readTree("""{"status":"answered","text":"One"}"""), mapper.readTree(results[0].output))
        assertEquals(mapper.readTree("""{"status":"cancelled"}"""), mapper.readTree(results[1].output))
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single().result
        assertEquals(listOf("first", "second"), completed.intermediateMessages.filter { it.kind == CoreChatMessageKind.TOOL_RESULT }.map { it.toolCallId })
        assertEquals(2, events.filterIsInstance<ChatStreamEvent.ToolResultAvailable>().size)
    }

    private fun assertSuccessfulAnswer(fixture: Fixture, events: List<ChatStreamEvent>, expected: Map<String, String>) {
        assertEquals(2, fixture.invocations.size)
        val event = events.filterIsInstance<ChatStreamEvent.ToolResultAvailable>().single()
        assertFalse(event.isError)
        assertEquals("call-question", event.toolCallId)
        assertEquals(OpenRouterChatEngine.ASK_USER_TOOL, event.name)
        assertEquals(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(expected), mapper.readTree(event.result))
        val prompt = fixture.invocations.last().prompt
        val result = prompt.toolResults().single()
        assertEquals(event.toolCallId, result.id)
        assertEquals(event.name, result.tool)
        assertFalse(result.isError)
        assertEquals(mapper.readTree(event.result), mapper.readTree(result.output))
        val call = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals(result.id, call.id)
        assertEquals(result.tool, call.tool)
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single().result
        assertEquals("Done", completed.content)
        val persisted = completed.intermediateMessages.single { it.kind == CoreChatMessageKind.TOOL_RESULT }
        assertEquals(event.result, persisted.content)
        assertEquals(event.toolCallId, persisted.toolCallId)
        assertEquals(event.name, persisted.toolName)
        assertFalse(persisted.isError)
    }

    private fun assertToolError(fixture: Fixture, events: List<ChatStreamEvent>) {
        val result = events.filterIsInstance<ChatStreamEvent.ToolResultAvailable>().single()
        assertTrue(result.isError)
        assertTrue(result.result.startsWith("Ask user failed: "))
        assertTrue(result.result.removePrefix("Ask user failed: ").isNotBlank())
        assertEquals(2, fixture.invocations.size)
        val promptResult = fixture.invocations.last().prompt.toolResults().single()
        assertTrue(promptResult.isError)
        assertEquals(result.result, promptResult.output)
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single().result
        assertTrue(completed.intermediateMessages.single { it.kind == CoreChatMessageKind.TOOL_RESULT }.isError)
    }

    private data class Invocation(val prompt: Prompt, val tools: List<ToolDescriptor>)
    private data class Fixture(
        val engine: OpenRouterChatEngine,
        val client: OpenRouterLLMClient,
        val invocations: CopyOnWriteArrayList<Invocation>
    )

    private fun fixture(
        handler: AskUserHandler?,
        rawArguments: String = arguments,
        toolsEnabled: Boolean = true,
        firstPass: Flow<StreamFrame> = flowOf(toolCall(rawArguments))
    ): Fixture {
        val client = mockk<OpenRouterLLMClient>(relaxed = true)
        val invocations = CopyOnWriteArrayList<Invocation>()
        val calls = AtomicInteger()
        coEvery { client.executeStreaming(any(), any(), any()) } answers {
            invocations += Invocation(firstArg(), thirdArg<List<ToolDescriptor>>().toList())
            if (calls.getAndIncrement() == 0) firstPass else flowOf(StreamFrame.TextDelta(text = "Done"))
        }
        val engine = spyk(OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = object : WebSearchClient {
                override suspend fun search(query: String): Result<String> = Result.success("")
            },
            toolsEnabled = toolsEnabled,
            askUserHandler = handler
        )) {
            every { createOpenRouterClient() } returns client
        }
        return Fixture(engine, client, invocations)
    }

    private fun toolCall(rawArguments: String, id: String = "call-question", index: Int = 0) =
        StreamFrame.ToolCallComplete(id = id, name = OpenRouterChatEngine.ASK_USER_TOOL, content = rawArguments, index = index)

    private fun Prompt.toolResults() = messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()

    // The production engine switches to real IO. Keep signals and their timeout on a real
    // dispatcher too, rather than letting runTest's virtual clock race the IO worker.
    private fun engineTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(15_000, block)
        }
    }
}
