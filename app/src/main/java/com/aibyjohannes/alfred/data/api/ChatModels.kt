package com.aibyjohannes.alfred.data.api

import com.squareup.moshi.Json

data class ChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

data class ChatCompletionResponse(
    val id: String?,
    val choices: List<Choice>
) {
    data class Choice(
        val index: Int,
        val message: ChatMessage,
        @Json(name = "finish_reason") val finishReason: String?
    )
}

data class OpenRouterError(
    val error: ErrorDetails?
) {
    data class ErrorDetails(
        val message: String?,
        val type: String?,
        val code: String?
    )
}

