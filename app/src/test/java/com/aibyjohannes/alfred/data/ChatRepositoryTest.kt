package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.audio.OpenRouterAudioClient
import com.aibyjohannes.alfred.core.audio.OpenRouterTtsClient
import com.aibyjohannes.alfred.core.engine.OpenRouterChatEngine
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient
import com.aibyjohannes.alfred.data.api.ChatMessage
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatRepositoryTest {

    private val apiKeyStore = mockk<ApiKeyStore>()

    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        mockkConstructor(OpenRouterAudioClient::class)
        mockkConstructor(OpenRouterTtsClient::class)
        mockkConstructor(OpenRouterChatEngine::class)

        repository = ChatRepository(apiKeyStore)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `transcribeAudio success`() = runBlocking {
        val audioFile = mockk<File>()
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        every { apiKeyStore.loadSttModel() } returns "test-stt-model"
        
        coEvery { anyConstructed<OpenRouterAudioClient>().transcribe(audioFile) } returns Result.success("transcribed text")
        every { anyConstructed<OpenRouterAudioClient>().close() } just Runs

        val result = repository.transcribeAudio(audioFile)

        assertTrue(result.isSuccess)
        assertEquals("transcribed text", result.getOrNull())
    }

    @Test
    fun `transcribeAudio fails when API key missing`() = runBlocking {
        val audioFile = mockk<File>()
        every { apiKeyStore.loadOpenRouterKey() } returns null

        val result = repository.transcribeAudio(audioFile)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API key not configured") == true)
    }

    @Test
    fun `synthesizeSpeech success`() = runBlocking {
        val outputFile = mockk<File>()
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        every { apiKeyStore.loadTtsModel() } returns "test-tts-model"
        every { apiKeyStore.loadTtsVoice() } returns "test-tts-voice"

        coEvery { anyConstructed<OpenRouterTtsClient>().synthesize("hello", outputFile) } returns Result.success(outputFile)
        every { anyConstructed<OpenRouterTtsClient>().close() } just Runs

        val result = repository.synthesizeSpeech("hello", outputFile)

        assertTrue(result.isSuccess)
        assertEquals(outputFile, result.getOrNull())
    }

    @Test
    fun `sendMessage success`() = runBlocking {
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        every { apiKeyStore.loadModel() } returns "test-model"
        every { apiKeyStore.loadTickTickClientId() } returns null

        val mockTurnResult = mockk<ChatTurnResult>()
        every { mockTurnResult.content } returns "ai reply"
        
        coEvery { anyConstructed<OpenRouterChatEngine>().sendMessage(any(), any()) } returns Result.success(mockTurnResult)

        val chatMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = "user content")
        val result = repository.sendMessage("hello", listOf(chatMessage))

        assertTrue(result.isSuccess)
        assertEquals("ai reply", result.getOrNull())
    }

    @Test
    fun `streamMessage success`() = runBlocking {
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        every { apiKeyStore.loadModel() } returns "test-model"
        every { apiKeyStore.loadTickTickClientId() } returns null

        val flow = flowOf(mockk<ChatStreamEvent>())
        every { anyConstructed<OpenRouterChatEngine>().streamMessage(any(), any()) } returns flow

        val chatMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = "user content")
        val resultFlow = repository.streamMessage("hello", listOf(chatMessage))

        assertNotNull(resultFlow)
    }

    @Test
    fun `perplexity subagent search success`() = runBlocking {
        every { apiKeyStore.loadOpenRouterKey() } returns "test-key"
        mockkConstructor(PerplexitySearchClient::class)
        coEvery { anyConstructed<PerplexitySearchClient>().search("query") } returns Result.success("search result")

        val subAgent = ChatRepository.PerplexitySubAgent(apiKeyStore)
        val result = subAgent.webSearch("query")

        assertTrue(result.isSuccess)
        assertEquals("search result", result.getOrNull())
    }
}
