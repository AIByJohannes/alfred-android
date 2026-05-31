package com.aibyjohannes.alfred.data.local

import androidx.room.withTransaction
import com.aibyjohannes.alfred.data.api.ChatMessage

class RoomConversationStore(
    private val database: AppDatabase
) : ConversationStore {

    private val conversationDao = database.conversationDao()
    private val chatMessageDao = database.chatMessageDao()
    private val workspaceDao = database.workspaceDao()

    override suspend fun listWorkspaces(): List<WorkspaceSummary> {
        return workspaceDao.listWorkspaces().map { WorkspaceSummary(it.id, it.name) }
    }

    override suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary {
        return database.withTransaction {
            val active = workspaceDao.getActiveWorkspace()
            if (active != null) {
                WorkspaceSummary(active.id, active.name)
            } else {
                val workspaces = workspaceDao.listWorkspaces()
                val target = if (workspaces.isNotEmpty()) {
                    val first = workspaces.first()
                    workspaceDao.setActiveWorkspace(first.id)
                    first
                } else {
                    val newWs = WorkspaceEntity(
                        name = "Personal",
                        createdAtEpochMs = System.currentTimeMillis(),
                        isActive = true
                    )
                    val id = workspaceDao.insertWorkspace(newWs)
                    newWs.copy(id = id)
                }
                WorkspaceSummary(target.id, target.name)
            }
        }
    }

    override suspend fun createWorkspace(name: String): WorkspaceSummary {
        return database.withTransaction {
            workspaceDao.clearActiveWorkspaceFlag()
            val newWs = WorkspaceEntity(
                name = name,
                createdAtEpochMs = System.currentTimeMillis(),
                isActive = true
            )
            val id = workspaceDao.insertWorkspace(newWs)
            WorkspaceSummary(id, name)
        }
    }

    override suspend fun switchActiveWorkspace(workspaceId: Long): WorkspaceSummary {
        return database.withTransaction {
            val target = workspaceDao.getWorkspaceById(workspaceId)
                ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
            workspaceDao.clearActiveWorkspaceFlag()
            workspaceDao.setActiveWorkspace(workspaceId)
            WorkspaceSummary(target.id, target.name)
        }
    }

    override suspend fun renameWorkspace(workspaceId: Long, newName: String) {
        workspaceDao.updateWorkspaceName(workspaceId, newName)
    }

    override suspend fun deleteWorkspace(workspaceId: Long) {
        database.withTransaction {
            workspaceDao.deleteWorkspace(workspaceId)
        }
    }

    override suspend fun getOrCreateActiveConversation(): ConversationSummary {
        return database.withTransaction {
            val activeWorkspace = getOrCreateActiveWorkspace()
            val existingActive = conversationDao.getActiveConversation(activeWorkspace.id)
            if (existingActive != null) {
                existingActive.toSummary()
            } else {
                val conversations = conversationDao.listConversations(activeWorkspace.id)
                if (conversations.isNotEmpty()) {
                    val first = conversations.first()
                    conversationDao.clearActiveConversationFlag()
                    conversationDao.setActiveConversation(first.id)
                    first.copy(isActive = true).toSummary()
                } else {
                    createConversationInternal(activeWorkspace.id)
                }
            }
        }
    }

    override suspend fun listConversations(): List<ConversationSummary> {
        val activeWorkspace = getOrCreateActiveWorkspace()
        return conversationDao.listConversations(activeWorkspace.id).map { it.toSummary() }
    }

    override suspend fun createConversation(): ConversationSummary {
        return database.withTransaction {
            val activeWorkspace = getOrCreateActiveWorkspace()
            createConversationInternal(activeWorkspace.id)
        }
    }

    override suspend fun switchActiveConversation(conversationId: Long): ConversationSummary {
        return database.withTransaction {
            val target = conversationDao.getConversationById(conversationId)
                ?: throw IllegalArgumentException("Conversation not found: $conversationId")
            conversationDao.clearActiveConversationFlag()
            conversationDao.setActiveConversation(conversationId)
            target.copy(isActive = true).toSummary()
        }
    }

    override suspend fun loadMessages(conversationId: Long): List<StoredChatMessage> {
        return chatMessageDao.getMessagesForConversation(conversationId).map { entity ->
            StoredChatMessage(
                id = entity.id,
                role = entity.role,
                content = entity.content
            )
        }
    }

    override suspend fun appendMessage(conversationId: Long, role: String, content: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = role,
                    content = content,
                    createdAtEpochMs = now
                )
            )
            val conversation = conversationDao.getConversationById(conversationId)
                ?: throw IllegalArgumentException("Conversation not found: $conversationId")

            if (role == ChatMessage.ROLE_USER && conversation.title.isNullOrBlank()) {
                conversationDao.updateTitleAndTimestamp(
                    conversationId = conversationId,
                    title = buildConversationTitle(content),
                    updatedAtEpochMs = now
                )
            } else {
                conversationDao.updateConversationTimestamp(
                    conversationId = conversationId,
                    updatedAtEpochMs = now
                )
            }
        }
    }

    override suspend fun deleteConversation(conversationId: Long) {
        conversationDao.deleteConversation(conversationId)
    }

    private suspend fun createConversationInternal(workspaceId: Long): ConversationSummary {
        val now = System.currentTimeMillis()
        conversationDao.clearActiveConversationFlag()
        val id = conversationDao.insertConversation(
            ConversationEntity(
                title = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                isActive = true,
                workspaceId = workspaceId
            )
        )
        return ConversationSummary(
            id = id,
            title = null,
            updatedAtEpochMs = now
        )
    }

    private fun ConversationEntity.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }

    private fun buildConversationTitle(userMessage: String): String {
        val trimmed = userMessage.trim()
        val maxLength = 40
        return if (trimmed.length <= maxLength) {
            trimmed
        } else {
            trimmed.take(maxLength - 1) + "..."
        }
    }
}
