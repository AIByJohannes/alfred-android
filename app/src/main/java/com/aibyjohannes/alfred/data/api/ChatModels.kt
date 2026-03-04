package com.aibyjohannes.alfred.data.api

/**
 * Simple chat message used for tracking conversation history in the ViewModel.
 * API communication now uses the official OpenAI Java SDK types.
 */
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
