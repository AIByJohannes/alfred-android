package com.aibyjohannes.alfred.core.model

sealed interface ChatStreamEvent {
    data class PassStarted(val passIndex: Int) : ChatStreamEvent

    data class TextDelta(val passIndex: Int, val textChunk: String) : ChatStreamEvent

    data class ReasoningDelta(
        val passIndex: Int,
        val id: String?,
        val textChunk: String?,
        val summaryChunk: String?
    ) : ChatStreamEvent

    data class ReasoningComplete(
        val passIndex: Int,
        val id: String?,
        val content: List<String>,
        val summary: List<String>,
        val encrypted: String?
    ) : ChatStreamEvent

    data class ToolCallDelta(
        val passIndex: Int,
        val id: String?,
        val name: String?,
        val argumentsChunk: String
    ) : ChatStreamEvent

    data class ToolCallRequested(
        val passIndex: Int,
        val toolCallId: String?,
        val name: String,
        val argumentsJson: String
    ) : ChatStreamEvent

    data class ToolResultAvailable(
        val passIndex: Int,
        val toolCallId: String?,
        val name: String,
        val resultPreview: String,
        val isError: Boolean
    ) : ChatStreamEvent

    data class PassCompleted(val passIndex: Int) : ChatStreamEvent

    data class Completed(val result: ChatTurnResult) : ChatStreamEvent
}
