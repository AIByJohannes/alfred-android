package com.aibyjohannes.alfred.data.api

/**
 * Simple chat message used for tracking conversation history in the ViewModel.
 * API communication now uses the official OpenAI Java SDK types.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val kind: String = KIND_MESSAGE,
    val turnId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgumentsJson: String? = null,
    val isError: Boolean = false,
    val reasoningText: String? = null,
    val reasoningSummary: String? = null,
    val encryptedReasoning: String? = null,
    val includeInPrompt: Boolean = true,
    val searchable: Boolean = true
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        const val KIND_MESSAGE = "message"
        const val KIND_REASONING = "reasoning"
        const val KIND_TOOL_CALL = "tool_call"
        const val KIND_TOOL_RESULT = "tool_result"
    }
}
