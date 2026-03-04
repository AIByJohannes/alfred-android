package com.aibyjohannes.alfred.data.local

import androidx.room.withTransaction
import com.aibyjohannes.alfred.data.api.ChatMessage

class RoomConversationStore(
    private val database: AppDatabase
) : ConversationStore {

    private val conversationDao = database.conversationDao()
    private val chatMessageDao = database.chatMessageDao()

    override suspend fun getOrCreateActiveConversation(): ConversationSummary {
        return database.withTransaction {
            val existingActive = conversationDao.getActiveConversation()
            if (existingActive != null) {
                existingActive.toSummary()
            } else {
                createConversationInternal()
            }
        }
    }

    override suspend fun listConversations(): List<ConversationSummary> {
        return conversationDao.listConversations().map { it.toSummary() }
    }

    override suspend fun createConversation(): ConversationSummary {
        return database.withTransaction {
            createConversationInternal()
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

    private suspend fun createConversationInternal(): ConversationSummary {
        val now = System.currentTimeMillis()
        conversationDao.clearActiveConversationFlag()
        val id = conversationDao.insertConversation(
            ConversationEntity(
                title = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                isActive = true
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
