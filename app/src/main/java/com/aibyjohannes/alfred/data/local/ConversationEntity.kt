package com.aibyjohannes.alfred.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isActive: Boolean
)
