package com.aibyjohannes.alfred.core.model

data class ToolCallTrace(
    val id: String? = null,
    val name: String,
    val argumentsJson: String,
    val resultPreview: String,
    val isError: Boolean
)

enum class CoreChatMessageKind {
    MESSAGE,
    REASONING,
    TOOL_CALL,
    TOOL_RESULT
}

data class ChatTurnResult(
    val content: String,
    val toolCalls: List<ToolCallTrace>,
    val intermediateMessages: List<CoreChatMessage> = emptyList()
)
