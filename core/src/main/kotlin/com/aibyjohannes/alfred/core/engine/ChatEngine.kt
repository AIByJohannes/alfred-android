package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage

interface ChatEngine {
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult>
}
