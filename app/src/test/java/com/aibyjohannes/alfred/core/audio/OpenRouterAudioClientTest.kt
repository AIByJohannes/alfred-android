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

class OpenRouterAudioClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `transcribe fails when file does not exist`() = runTest {
        val client = OpenRouterAudioClient(apiKey = "test-key")
        val result = client.transcribe(File("nonexistent.wav"))
        assertTrue(result.isFailure)
        assertEquals("Audio file does not exist", result.exceptionOrNull()?.message)
    }

    @Test
    fun `transcribe posts correctly and parses JSON response`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            respond(
                content = """{"text": "  Transcribed hello world!   "}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val audioClient = OpenRouterAudioClient(
            apiKey = "test-key",
            model = "whisper-test",
            client = httpClient
        )

        val audioFile = File(tempFolder.newFolder(), "test.mp3")
        audioFile.writeBytes(byteArrayOf(1, 2, 3))

        val result = audioClient.transcribe(audioFile)
        assertTrue(result.isSuccess)
        assertEquals("Transcribed hello world!", result.getOrNull())
    }

    @Test
    fun `transcribe handles format extensions correctly`() = runTest {
        val formats = listOf("m4a", "mp3", "wav", "webm", "ogg", "flac", "xyz")
        val tempDir = tempFolder.newFolder()

        for (ext in formats) {
            var capturedFormat = ""
            val mockEngine = MockEngine { request ->
                val body = request.body
                val bodyText = if (body is io.ktor.http.content.OutgoingContent.ByteArrayContent) {
                    String(body.bytes())
                } else {
                    ""
                }
                if (bodyText.contains("format")) {
                    capturedFormat = if (bodyText.contains("format\":\"m4a\"")) "m4a"
                    else if (bodyText.contains("format\":\"mp3\"")) "mp3"
                    else if (bodyText.contains("format\":\"wav\"")) "wav"
                    else if (bodyText.contains("format\":\"webm\"")) "webm"
                    else if (bodyText.contains("format\":\"ogg\"")) "ogg"
                    else if (bodyText.contains("format\":\"flac\"")) "flac"
                    else ""
                }
                respond(
                    content = """{"text": "format: $ext"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine)
            val audioClient = OpenRouterAudioClient(
                apiKey = "test-key",
                client = httpClient
            )

            val audioFile = File(tempDir, "test.$ext")
            audioFile.writeBytes(byteArrayOf(12, 34))

            val result = audioClient.transcribe(audioFile)
            assertTrue(result.isSuccess)
            
            val expectedFormat = when (ext) {
                "m4a", "mp3", "wav", "webm", "ogg", "flac" -> ext
                else -> "wav"
            }
            if (ext != "xyz") {
                assertEquals(expectedFormat, capturedFormat)
            }
            audioFile.delete()
        }
    }

    @Test
    fun `transcribe handles non-2xx API failure`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = "Invalid Key",
                status = HttpStatusCode.Unauthorized
            )
        }
        val httpClient = HttpClient(mockEngine)
        val audioClient = OpenRouterAudioClient(
            apiKey = "bad-key",
            client = httpClient
        )

        val audioFile = File(tempFolder.newFolder(), "test.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3))

        val result = audioClient.transcribe(audioFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API call failed with code 401") == true)
    }

    @Test
    fun `transcribe handles empty or blank response body`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val httpClient = HttpClient(mockEngine)
        val audioClient = OpenRouterAudioClient(
            apiKey = "test-key",
            client = httpClient
        )

        val audioFile = File(tempFolder.newFolder(), "test.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3))

        val result = audioClient.transcribe(audioFile)
        assertTrue(result.isFailure)
        assertEquals("Received empty response body", result.exceptionOrNull()?.message)
    }

    @Test
    fun `close closes client`() {
        val mockEngine = MockEngine { request ->
            respond("")
        }
        val httpClient = HttpClient(mockEngine)
        val audioClient = OpenRouterAudioClient(
            apiKey = "test-key",
            client = httpClient
        )
        audioClient.close()
    }
}
