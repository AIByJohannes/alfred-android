package com.aibyjohannes.alfred.data.local

data class ConversationSummary(
    val id: Long,
    val title: String?,
    val updatedAtEpochMs: Long
)

data class StoredChatMessage(
    val id: Long,
    val role: String,
    val content: String
)

interface ConversationStore {
    suspend fun getOrCreateActiveConversation(): ConversationSummary
    suspend fun listConversations(): List<ConversationSummary>
    suspend fun createConversation(): ConversationSummary
    suspend fun switchActiveConversation(conversationId: Long): ConversationSummary
    suspend fun loadMessages(conversationId: Long): List<StoredChatMessage>
    suspend fun appendMessage(conversationId: Long, role: String, content: String)
    suspend fun deleteConversation(conversationId: Long)
}
