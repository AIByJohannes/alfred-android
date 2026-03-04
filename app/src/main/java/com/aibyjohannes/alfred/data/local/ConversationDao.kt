package com.aibyjohannes.alfred.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochMs DESC")
    suspend fun listConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveConversation(): ConversationEntity?

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET isActive = 0")
    suspend fun clearActiveConversationFlag()

    @Query("UPDATE conversations SET isActive = 1 WHERE id = :conversationId")
    suspend fun setActiveConversation(conversationId: Long)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :conversationId")
    suspend fun updateTitleAndTimestamp(
        conversationId: Long,
        title: String,
        updatedAtEpochMs: Long
    )

    @Query("UPDATE conversations SET updatedAtEpochMs = :updatedAtEpochMs WHERE id = :conversationId")
    suspend fun updateConversationTimestamp(conversationId: Long, updatedAtEpochMs: Long)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)
}
