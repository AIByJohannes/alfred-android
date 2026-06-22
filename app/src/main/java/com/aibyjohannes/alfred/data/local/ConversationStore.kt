package com.aibyjohannes.alfred.data.local

import kotlinx.coroutines.flow.Flow

data class ConversationSummary(
    val id: String,
    val title: String?,
    val updatedAtEpochMs: Long
)

data class WorkspaceSummary(
    val id: String,
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
    fun observeWorkspaces(): Flow<List<WorkspaceSummary>>
    suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary
    suspend fun createWorkspace(name: String): WorkspaceSummary
    suspend fun switchActiveWorkspace(workspaceId: String): WorkspaceSummary
    suspend fun renameWorkspace(workspaceId: String, newName: String)
    suspend fun deleteWorkspace(workspaceId: String)

    // Conversation Operations (scoped to active workspace)
    suspend fun getOrCreateActiveConversation(): ConversationSummary
    suspend fun listConversations(): List<ConversationSummary>
    fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>>
    suspend fun createConversation(): ConversationSummary
    suspend fun switchActiveConversation(conversationId: String): ConversationSummary
    suspend fun loadMessages(conversationId: String): List<StoredChatMessage>
    suspend fun appendMessage(conversationId: String, role: String, content: String)
    suspend fun appendMessages(conversationId: String, messages: List<ConversationMessageDraft>)
    suspend fun deleteConversation(conversationId: String)
}
