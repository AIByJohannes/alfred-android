package com.aibyjohannes.alfred.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAtEpochMs ASC")
    suspend fun listWorkspaces(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveWorkspace(): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE id = :workspaceId LIMIT 1")
    suspend fun getWorkspaceById(workspaceId: Long): WorkspaceEntity?

    @Insert
    suspend fun insertWorkspace(workspace: WorkspaceEntity): Long

    @Query("UPDATE workspaces SET isActive = 0")
    suspend fun clearActiveWorkspaceFlag()

    @Query("UPDATE workspaces SET isActive = 1 WHERE id = :workspaceId")
    suspend fun setActiveWorkspace(workspaceId: Long)

    @Query("UPDATE workspaces SET name = :name WHERE id = :workspaceId")
    suspend fun updateWorkspaceName(workspaceId: Long, name: String)

    @Query("DELETE FROM workspaces WHERE id = :workspaceId")
    suspend fun deleteWorkspace(workspaceId: Long)
}
