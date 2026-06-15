package com.aibyjohannes.alfred.core.audio

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Client for OpenRouter's text-to-speech endpoint (/api/v1/audio/speech).
 * Returns an MP3 file that can be played with Android's MediaPlayer.
 */
class OpenRouterTtsClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val voice: String = DEFAULT_VOICE,
    private val client: HttpClient = createDefaultClient()
) : AutoCloseable {

    private val objectMapper = ObjectMapper()

    /**
     * Synthesizes [text] into speech and writes the audio bytes to [outputFile].
     * The output file will be an MP3.
     */
    suspend fun synthesize(text: String, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) {
                return@withContext Result.failure(Exception("Cannot synthesize empty text"))
            }

            val requestBodyMap = mapOf(
                "model" to model,
                "input" to text,
                "voice" to voice,
                "response_format" to "mp3"
            )
            val jsonRequestBody = objectMapper.writeValueAsString(requestBodyMap)

            val response = client.post("https://openrouter.ai/api/v1/audio/speech") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(jsonRequestBody)
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                return@withContext Result.failure(
                    Exception("TTS API failed with code ${response.status.value}: $errorBody")
                )
            }

            val audioBytes = response.bodyAsBytes()
            if (audioBytes.isEmpty()) {
                return@withContext Result.failure(Exception("Received empty audio response"))
            }

            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(audioBytes)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_MODEL = "hexgrad/kokoro-82m"
        const val DEFAULT_VOICE = "af_alloy"

        fun createDefaultClient(): HttpClient {
            return HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }
        }
    }
}
