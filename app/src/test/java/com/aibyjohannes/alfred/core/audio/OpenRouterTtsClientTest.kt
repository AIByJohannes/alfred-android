package com.aibyjohannes.alfred.core.audio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenRouterTtsClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `synthesize fails on empty text`() = runTest {
        val client = OpenRouterTtsClient(apiKey = "test-key")
        val outputFile = File(tempFolder.newFolder(), "output.mp3")
        val result = client.synthesize("", outputFile)
        assertTrue(result.isFailure)
        assertEquals("Cannot synthesize empty text", result.exceptionOrNull()?.message)
    }

    @Test
    fun `synthesize makes correct post request and writes response bytes`() = runTest {
        val mockData = byteArrayOf(1, 2, 3, 4)
        val mockEngine = MockEngine { request ->
            assertEquals("https://openrouter.ai/api/v1/audio/speech", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            respond(
                content = mockData,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "audio/mpeg")
            )
        }
        val httpClient = HttpClient(mockEngine)
        
        val ttsClient = OpenRouterTtsClient(
            apiKey = "test-key",
            model = "test-model",
            voice = "test-voice",
            client = httpClient
        )
        
        val outputFile = File(tempFolder.newFolder(), "output.mp3")
        val result = ttsClient.synthesize("Hello World", outputFile)
        
        assertTrue(result.isSuccess)
        assertEquals(outputFile.absolutePath, result.getOrThrow().absolutePath)
        assertTrue(outputFile.exists())
        assertEquals(4, outputFile.readBytes().size)
        assertEquals(1.toByte(), outputFile.readBytes()[0])
    }

    @Test
    fun `synthesize handles non-2xx API error response`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = "API Rate Limit Exceeded",
                status = HttpStatusCode.TooManyRequests
            )
        }
        val httpClient = HttpClient(mockEngine)
        val ttsClient = OpenRouterTtsClient(
            apiKey = "test-key",
            client = httpClient
        )
        
        val outputFile = File(tempFolder.newFolder(), "output.mp3")
        val result = ttsClient.synthesize("Hello World", outputFile)
        
        assertTrue(result.isFailure)
        val errorMsg = result.exceptionOrNull()?.message
        assertTrue(errorMsg != null && errorMsg.contains("TTS API failed with code 429"))
    }
}
