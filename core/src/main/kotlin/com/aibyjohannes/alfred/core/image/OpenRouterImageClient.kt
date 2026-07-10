package com.aibyjohannes.alfred.core.image

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
import java.util.Base64

data class GeneratedImage(
    val bytes: ByteArray,
    val mediaType: String
)

class OpenRouterImageClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val client: HttpClient = HttpClient(OkHttp)
) : AutoCloseable {
    private val objectMapper = ObjectMapper()

    suspend fun generate(prompt: String): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        try {
            val trimmedPrompt = prompt.trim()
            if (trimmedPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Image prompt cannot be blank."))

            val response = client.post("https://openrouter.ai/api/v1/images") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(objectMapper.writeValueAsString(mapOf("model" to model, "prompt" to trimmedPrompt)))
            }
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Image generation failed with code ${response.status.value}: $body"))
            }
            val image = objectMapper.readTree(body).path("data").firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Image generation returned no image."))
            val base64 = image.path("b64_json").asText().takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Image generation returned no image data."))
            Result.success(GeneratedImage(Base64.getDecoder().decode(base64), image.path("media_type").asText("image/png")))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun close() = client.close()

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-image-1"
    }
}
