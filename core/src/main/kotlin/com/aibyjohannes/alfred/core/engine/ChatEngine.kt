package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatEngine {
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult>

    fun streamMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Flow<ChatStreamEvent>
}
