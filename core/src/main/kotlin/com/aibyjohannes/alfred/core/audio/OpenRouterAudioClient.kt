package com.aibyjohannes.alfred.core.audio

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

class OpenRouterAudioClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val client: HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp)
) : AutoCloseable {

    private val objectMapper = ObjectMapper()

    suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists()) {
                return@withContext Result.failure(Exception("Audio file does not exist"))
            }

            val bytes = audioFile.readBytes()
            val base64Data = Base64.getEncoder().encodeToString(bytes)

            val format = when (audioFile.extension.lowercase()) {
                "m4a" -> "m4a"
                "mp3" -> "mp3"
                "wav" -> "wav"
                "webm" -> "webm"
                "ogg" -> "ogg"
                "flac" -> "flac"
                "aac" -> "aac"
                else -> "wav"
            }

            val requestBodyMap = mapOf(
                "model" to model,
                "input_audio" to mapOf(
                    "data" to base64Data,
                    "format" to format
                )
            )
            val jsonRequestBody = objectMapper.writeValueAsString(requestBodyMap)

            val response = client.post("https://openrouter.ai/api/v1/audio/transcriptions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(jsonRequestBody)
            }

            val responseBody = response.bodyAsText()
            if (response.status.value !in 200..299) {
                return@withContext Result.failure(
                    Exception("API call failed with code ${response.status.value}: $responseBody")
                )
            }

            if (responseBody.isBlank()) {
                return@withContext Result.failure(Exception("Received empty response body"))
            }

            val node = objectMapper.readTree(responseBody)
            val text = node.path("text").asText("").trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_MODEL = "openai/whisper-1"
    }
}
