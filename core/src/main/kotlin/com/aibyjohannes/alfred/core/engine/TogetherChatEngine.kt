package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class TogetherChatEngine(
    private val apiKey: String,
    private val model: String = PRISM_MODEL,
    private val systemPrompt: String,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ChatEngine {
    override suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult> = runCatching {
        val content = complete(userMessage, conversationHistory)
        ChatTurnResult(
            content = content,
            toolCalls = emptyList(),
            intermediateMessages = listOf(
                CoreChatMessage(role = "assistant", content = content, kind = CoreChatMessageKind.MESSAGE)
            )
        )
    }

    override fun streamMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.PassStarted(0))
        val result = sendMessage(userMessage, conversationHistory).getOrThrow()
        emit(ChatStreamEvent.TextDelta(0, result.content))
        emit(ChatStreamEvent.PassCompleted(0))
        emit(ChatStreamEvent.Completed(result))
    }

    private suspend fun complete(userMessage: String, conversationHistory: List<CoreChatMessage>): String =
        withContext(Dispatchers.IO) {
            HttpClient(OkHttp).use { client ->
                val messages = buildList {
                    add(mapOf("role" to "system", "content" to systemPrompt))
                    conversationHistory.filter { it.includeInPrompt && it.kind == CoreChatMessageKind.MESSAGE }
                        .forEach { message ->
                            val role = when (message.role) {
                                "assistant" -> "assistant"
                                "system" -> "system"
                                else -> "user"
                            }
                            add(mapOf("role" to role, "content" to message.content))
                        }
                    add(mapOf("role" to "user", "content" to userMessage))
                }
                val response = client.post(CHAT_ENDPOINT) {
                    header("Authorization", "Bearer $apiKey")
                    header("User-Agent", "Alfred-Android/1.0")
                    contentType(ContentType.Application.Json)
                    setBody(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "model" to model,
                                "messages" to messages,
                                "temperature" to 0.7,
                                "top_p" to 0.95,
                                "top_k" to 20
                            )
                        )
                    )
                }
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    error("PrismML request failed (${response.status.value}): ${friendlyError(body)}")
                }
                objectMapper.readTree(body).path("choices").firstOrNull()?.path("message")?.path("content")
                    ?.asText()?.takeIf(String::isNotBlank)
                    ?: error("PrismML returned no response content.")
            }
        }

    private fun friendlyError(body: String): String = runCatching {
        objectMapper.readTree(body).path("error").path("message").asText().takeIf(String::isNotBlank)
    }.getOrNull() ?: body.take(500)

    companion object {
        const val MODEL_PREFIX = "together/"
        const val PRISM_MODEL = "Prism-ML/Ternary-Bonsai-27B"
        const val MODEL_ID = MODEL_PREFIX + PRISM_MODEL
        const val CHAT_ENDPOINT = "https://api.together.xyz/v1/chat/completions"
    }
}
