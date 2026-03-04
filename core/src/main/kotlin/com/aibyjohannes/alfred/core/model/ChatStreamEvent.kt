package com.aibyjohannes.alfred.core.model

sealed interface ChatStreamEvent {
    data class Delta(val textChunk: String) : ChatStreamEvent

    data class Completed(val result: ChatTurnResult) : ChatStreamEvent
}
