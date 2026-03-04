package com.aibyjohannes.alfred.core.model

data class ToolCallTrace(
    val name: String,
    val argumentsJson: String,
    val resultPreview: String,
    val isError: Boolean
)

data class ChatTurnResult(
    val content: String,
    val toolCalls: List<ToolCallTrace>
)
