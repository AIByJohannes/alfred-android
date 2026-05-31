package com.aibyjohannes.alfred.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val createdAtEpochMs: Long,
    val isActive: Boolean = false
)
