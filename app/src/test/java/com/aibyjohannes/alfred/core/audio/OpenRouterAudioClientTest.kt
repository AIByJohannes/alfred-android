package com.aibyjohannes.alfred.core.audio

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class OpenRouterAudioClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val objectMapper = ObjectMapper()

    @Test
    fun `transcribe fails when the selected file does not exist`() = runTest {
        val missingFile = File(tempFolder.newFolder(), "missing.wav")

        val result = OpenRouterAudioClient(apiKey = "test-key").use { client ->
            client.transcribe(missingFile)
        }

        assertTrue(result.isFailure)
        assertEquals("Audio file does not exist", result.exceptionOrNull()?.message)
    }

    @Test
    fun `transcribe sends the documented OpenRouter request and returns trimmed text`() = runTest {
        val audioBytes = byteArrayOf(1, 2, 3)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals(ContentType.Application.Json, request.body.contentType)

            val requestJson = objectMapper.readTree(request.bodyText())
            assertEquals("whisper-test", requestJson.path("model").asText())
            assertEquals(Base64.getEncoder().encodeToString(audioBytes), requestJson.path("input_audio").path("data").asText())
            assertEquals("mp3", requestJson.path("input_audio").path("format").asText())

            respond(
                content = """{"text": "  Transcribed hello world!   "}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val audioFile = File(tempFolder.newFolder(), "test.mp3").apply { writeBytes(audioBytes) }

        val result = OpenRouterAudioClient(
            apiKey = "test-key",
            model = "whisper-test",
            client = HttpClient(mockEngine)
        ).use { client -> client.transcribe(audioFile) }

        assertEquals("Transcribed hello world!", result.getOrThrow())
    }

    @Test
    fun `transcribe declares supported formats case insensitively and falls back to wav`() = runTest {
        val formats = listOf(
            "M4A" to "m4a",
            "mp3" to "mp3",
            "wav" to "wav",
            "webm" to "webm",
            "ogg" to "ogg",
            "flac" to "flac",
            "aac" to "aac",
            "unknown" to "wav"
        )
        val tempDir = tempFolder.newFolder()

        formats.forEach { (extension, expectedFormat) ->
            var actualFormat: String? = null
            val mockEngine = MockEngine { request ->
                actualFormat = objectMapper.readTree(request.bodyText())
                    .path("input_audio")
                    .path("format")
                    .asText()
                respond(
                    content = """{"text":"ok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json")
                )
            }
            val audioFile = File(tempDir, "test.$extension").apply { writeBytes(byteArrayOf(12, 34)) }

            val result = OpenRouterAudioClient("test-key", client = HttpClient(mockEngine)).use { client ->
                client.transcribe(audioFile)
            }

            assertEquals("ok", result.getOrThrow())
            assertEquals(expectedFormat, actualFormat)
        }
    }

    @Test
    fun `transcribe returns status and response details for API failures`() = runTest {
        val mockEngine = MockEngine {
            respond(content = "Invalid Key", status = HttpStatusCode.Unauthorized)
        }
        val audioFile = File(tempFolder.newFolder(), "test.wav").apply { writeBytes(byteArrayOf(1)) }

        val result = OpenRouterAudioClient("bad-key", client = HttpClient(mockEngine)).use { client ->
            client.transcribe(audioFile)
        }

        assertTrue(result.isFailure)
        assertEquals("API call failed with code 401: Invalid Key", result.exceptionOrNull()?.message)
    }

    @Test
    fun `transcribe rejects empty and whitespace-only response bodies`() = runTest {
        listOf("", "   \n").forEach { responseBody ->
            val mockEngine = MockEngine {
                respond(content = responseBody, status = HttpStatusCode.OK)
            }
            val audioFile = File(tempFolder.newFolder(), "test.wav").apply { writeBytes(byteArrayOf(1)) }

            val result = OpenRouterAudioClient("test-key", client = HttpClient(mockEngine)).use { client ->
                client.transcribe(audioFile)
            }

            assertTrue(result.isFailure)
            assertEquals("Received empty response body", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `close releases the owned HTTP client`() {
        val httpClient = mockk<HttpClient>()
        every { httpClient.close() } just Runs

        OpenRouterAudioClient(apiKey = "test-key", client = httpClient).close()

        verify(exactly = 1) { httpClient.close() }
    }

    private fun io.ktor.client.request.HttpRequestData.bodyText(): String {
        val content = body as? OutgoingContent.ByteArrayContent
            ?: error("Expected a byte-array request body, got ${body::class.simpleName}")
        return content.bytes().decodeToString()
    }
}
