package com.aibyjohannes.alfred.data.local

data class ConversationSummary(
    val id: Long,
    val title: String?,
    val updatedAtEpochMs: Long
)

data class WorkspaceSummary(
    val id: Long,
    val name: String
)

data class StoredChatMessage(
    val id: Long,
    val role: String,
    val content: String
)

interface ConversationStore {
    // Workspace Operations
    suspend fun listWorkspaces(): List<WorkspaceSummary>
    suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary
    suspend fun createWorkspace(name: String): WorkspaceSummary
    suspend fun switchActiveWorkspace(workspaceId: Long): WorkspaceSummary
    suspend fun renameWorkspace(workspaceId: Long, newName: String)
    suspend fun deleteWorkspace(workspaceId: Long)

    // Conversation Operations (scoped to active workspace)
    suspend fun getOrCreateActiveConversation(): ConversationSummary
    suspend fun listConversations(): List<ConversationSummary>
    suspend fun createConversation(): ConversationSummary
    suspend fun switchActiveConversation(conversationId: Long): ConversationSummary
    suspend fun loadMessages(conversationId: Long): List<StoredChatMessage>
    suspend fun appendMessage(conversationId: Long, role: String, content: String)
    suspend fun deleteConversation(conversationId: Long)
}
