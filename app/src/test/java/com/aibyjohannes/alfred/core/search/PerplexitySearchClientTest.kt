package com.aibyjohannes.alfred.core.search

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Optional

class PerplexitySearchClientTest {

    private val mockClient = mockk<OpenAIClient>()
    private val mockChatService = mockk<ChatService>()
    private val mockCompletionsService = mockk<ChatCompletionService>()
    private val mockResponse = mockk<ChatCompletion>()
    private val mockChoice = mockk<ChatCompletion.Choice>()
    private val mockMessage = mockk<ChatCompletionMessage>()

    @Before
    fun setUp() {
        mockkConstructor(OpenAIOkHttpClient.Builder::class)
        every { anyConstructed<OpenAIOkHttpClient.Builder>().apiKey(any<String>()) } answers { invocation.self as OpenAIOkHttpClient.Builder }
        every { anyConstructed<OpenAIOkHttpClient.Builder>().baseUrl(any<String>()) } answers { invocation.self as OpenAIOkHttpClient.Builder }
        every { anyConstructed<OpenAIOkHttpClient.Builder>().build() } returns mockClient

        every { mockClient.chat() } returns mockChatService
        every { mockChatService.completions() } returns mockCompletionsService
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `search success returns content`() = runBlocking {
        val client = PerplexitySearchClient("test-api-key")

        every { mockCompletionsService.create(any()) } returns mockResponse
        every { mockResponse.choices() } returns listOf(mockChoice)
        every { mockChoice.message() } returns mockMessage
        every { mockMessage.content() } returns Optional.of("perplexity search response text")

        val result = client.search("query")

        assertTrue(result.isSuccess)
        assertEquals("perplexity search response text", result.getOrNull())
    }

    @Test
    fun `search failure when empty response choices`() = runBlocking {
        val client = PerplexitySearchClient("test-api-key")

        every { mockCompletionsService.create(any()) } returns mockResponse
        every { mockResponse.choices() } returns emptyList()

        val result = client.search("query")

        assertTrue(result.isFailure)
        assertEquals("No response from search model", result.exceptionOrNull()?.message)
    }

    @Test
    fun `search failure when exception thrown`() = runBlocking {
        val client = PerplexitySearchClient("test-api-key")

        every { mockCompletionsService.create(any()) } throws RuntimeException("Connection timeout")

        val result = client.search("query")

        assertTrue(result.isFailure)
        assertEquals("Web search failed: Connection timeout", result.exceptionOrNull()?.message)
    }
}
