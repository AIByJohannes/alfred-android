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
    val content: String,
    val kind: String = "message",
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
)

data class ConversationMessageDraft(
    val role: String,
    val content: String,
    val kind: String = "message",
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
    suspend fun appendMessages(conversationId: Long, messages: List<ConversationMessageDraft>)
    suspend fun deleteConversation(conversationId: Long)
}
