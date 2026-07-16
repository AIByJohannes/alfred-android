package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.audio.OpenRouterAudioClient
import com.aibyjohannes.alfred.core.audio.OpenRouterTtsClient
import com.aibyjohannes.alfred.core.engine.ChatEngine
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatRepositoryTest {

    private val apiKeyStore = mockk<ApiKeyStore>()

    @Before
    fun setUp() {
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        every { apiKeyStore.loadModel() } returns "test-chat-model"
        every { apiKeyStore.loadSttModel() } returns "test-stt-model"
        every { apiKeyStore.loadTtsModel() } returns "test-tts-model"
        every { apiKeyStore.loadTtsVoice() } returns "test-voice"
        every { apiKeyStore.isEfficiencyModeEnabled() } returns false
        every { apiKeyStore.isPrivacyModeEnabled() } returns false
    }

    @Test
    fun `transcribeAudio forwards selected credentials and closes the client`() = runBlocking {
        val audioFile = mockk<File>()
        val audioClient = mockk<OpenRouterAudioClient>()
        var factoryArguments: Pair<String, String>? = null
        every { audioClient.close() } just Runs
        coEvery { audioClient.transcribe(audioFile) } returns Result.success("transcribed text")
        val repository = repository(
            audioFactory = { apiKey, model ->
                factoryArguments = apiKey to model
                audioClient
            }
        )

        val result = repository.transcribeAudio(audioFile)

        assertEquals("transcribed text", result.getOrThrow())
        assertEquals("test-key" to "test-stt-model", factoryArguments)
        coVerify(exactly = 1) { audioClient.transcribe(audioFile) }
        verify(exactly = 1) { audioClient.close() }
    }

    @Test
    fun `synthesizeSpeech forwards selected settings and closes the client`() = runBlocking {
        val outputFile = mockk<File>()
        val ttsClient = mockk<OpenRouterTtsClient>()
        var factoryArguments: Triple<String, String, String>? = null
        every { ttsClient.close() } just Runs
        coEvery { ttsClient.synthesize("hello", outputFile) } returns Result.success(outputFile)
        val repository = repository(
            ttsFactory = { apiKey, model, voice ->
                factoryArguments = Triple(apiKey, model, voice)
                ttsClient
            }
        )

        val result = repository.synthesizeSpeech("hello", outputFile)

        assertSame(outputFile, result.getOrThrow())
        assertEquals(Triple("test-key", "test-tts-model", "test-voice"), factoryArguments)
        coVerify(exactly = 1) { ttsClient.synthesize("hello", outputFile) }
        verify(exactly = 1) { ttsClient.close() }
    }

    @Test
    fun `null and blank API keys reject every live repository operation`() = runBlocking {
        listOf<String?>(null, "", "   ").forEach { missingKey ->
            every { apiKeyStore.loadOpenRouterKey() } returns missingKey
            var collaboratorCreated = false
            val repository = repository(
                audioFactory = { _, _ -> collaboratorCreated = true; mockk() },
                ttsFactory = { _, _, _ -> collaboratorCreated = true; mockk() },
                engineFactory = { collaboratorCreated = true; mockk() }
            )

            assertTrue(repository.transcribeAudio(mockk()).isFailure)
            assertTrue(repository.synthesizeSpeech("text", mockk()).isFailure)
            assertTrue(repository.sendMessage("hello", emptyList()).isFailure)
            assertThrows(IllegalStateException::class.java) {
                repository.streamMessage("hello", emptyList())
            }
            assertTrue(!collaboratorCreated)
        }
    }

    @Test
    fun `sendMessage forwards the complete conversation contract and returns assistant content`() = runBlocking {
        val engine = mockk<ChatEngine>()
        val historySlot = slot<List<CoreChatMessage>>()
        var engineRequest: ChatEngineRequest? = null
        coEvery { engine.sendMessage("new question", capture(historySlot)) } returns Result.success(
            ChatTurnResult(content = "assistant reply", toolCalls = emptyList())
        )
        val repository = repository(engineFactory = { request -> engineRequest = request; engine })
        val history = listOf(
            ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = "tool result",
                kind = ChatMessage.KIND_TOOL_RESULT,
                turnId = "turn-1",
                toolCallId = "call-1",
                toolName = "search",
                toolArgumentsJson = "{\"query\":\"weather\"}",
                isError = true,
                reasoningText = "reasoning",
                reasoningSummary = "summary",
                encryptedReasoning = "encrypted",
                includeInPrompt = false,
                searchable = false
            )
        )

        val result = repository.sendMessage("new question", history, sysInfo = "device context")

        assertEquals("assistant reply", result.getOrThrow())
        assertEquals(ChatEngineRequest("test-key", "test-chat-model", "device context", 10), engineRequest)
        val forwarded = historySlot.captured.single()
        assertEquals(ChatMessage.ROLE_ASSISTANT, forwarded.role)
        assertEquals("tool result", forwarded.content)
        assertEquals(CoreChatMessageKind.TOOL_RESULT, forwarded.kind)
        assertEquals("turn-1", forwarded.turnId)
        assertEquals("call-1", forwarded.toolCallId)
        assertEquals("search", forwarded.toolName)
        assertEquals("{\"query\":\"weather\"}", forwarded.toolArgumentsJson)
        assertTrue(forwarded.isError)
        assertEquals("reasoning", forwarded.reasoningText)
        assertEquals("summary", forwarded.reasoningSummary)
        assertEquals("encrypted", forwarded.encryptedReasoning)
        assertTrue(!forwarded.includeInPrompt)
        assertTrue(!forwarded.searchable)
    }

    @Test
    fun `streamMessage forwards max passes and emits the engine events`() = runBlocking {
        val engine = mockk<ChatEngine>()
        val event = ChatStreamEvent.TextDelta(passIndex = 2, textChunk = "chunk")
        val historySlot = slot<List<CoreChatMessage>>()
        var engineRequest: ChatEngineRequest? = null
        every { engine.streamMessage("hello", capture(historySlot)) } returns flowOf(event)
        val repository = repository(engineFactory = { request -> engineRequest = request; engine })
        val history = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "earlier"))

        val events = repository.streamMessage("hello", history, sysInfo = "sys", maxPasses = 4).toList()

        assertEquals(listOf(event), events)
        assertEquals(ChatEngineRequest("test-key", "test-chat-model", "sys", 4), engineRequest)
        assertEquals("earlier", historySlot.captured.single().content)
    }

    @Test
    fun `sendMessage preserves engine failures`() = runBlocking {
        val engine = mockk<ChatEngine>()
        val failure = IllegalStateException("upstream failed")
        coEvery { engine.sendMessage(any(), any()) } returns Result.failure(failure)
        val repository = repository(engineFactory = { engine })

        val result = repository.sendMessage("hello", emptyList())

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `local Gemma chat runs without an OpenRouter key`() = runBlocking {
        every { apiKeyStore.loadModel() } returns LocalGemmaModelStore.LOCAL_MODEL_ID
        every { apiKeyStore.loadOpenRouterKey() } returns null
        val engine = mockk<ChatEngine>()
        var configuredPath: String? = null
        var configuredPrompt: String? = null
        coEvery { engine.sendMessage("offline question", emptyList()) } returns Result.success(
            ChatTurnResult(content = "offline answer", toolCalls = emptyList())
        )
        val repository = ChatRepository(
            apiKeyStore = apiKeyStore,
            dependencies = ChatRepositoryDependencies(
                localModelPathProvider = { "C:/models/gemma-3n-e2b.litertlm" },
                localChatEngineFactory = { path, prompt ->
                    configuredPath = path
                    configuredPrompt = prompt
                    engine
                }
            )
        )

        val result = repository.sendMessage("offline question", emptyList(), sysInfo = "device context")

        assertEquals("offline answer", result.getOrThrow())
        assertEquals("C:/models/gemma-3n-e2b.litertlm", configuredPath)
        assertTrue(configuredPrompt.orEmpty().contains("device context"))
        verify(exactly = 0) { apiKeyStore.loadOpenRouterKey() }
    }

    private fun repository(
        audioFactory: (String, String) -> OpenRouterAudioClient = { _, _ -> mockk() },
        ttsFactory: (String, String, String) -> OpenRouterTtsClient = { _, _, _ -> mockk() },
        engineFactory: (ChatEngineRequest) -> ChatEngine = { mockk() }
    ): ChatRepository = ChatRepository(
        apiKeyStore = apiKeyStore,
        dependencies = ChatRepositoryDependencies(
            audioClientFactory = audioFactory,
            ttsClientFactory = ttsFactory,
            chatEngineFactory = engineFactory
        )
    )
}
