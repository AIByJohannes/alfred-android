package com.aibyjohannes.alfred.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal data class IndexedWorkspace(
    val id: String,
    val name: String,
    val folderName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deleted: Boolean
)

internal data class IndexedConversation(
    val id: String,
    val workspaceId: String,
    val filePath: List<String>,
    val title: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deleted: Boolean,
    val messages: List<IndexedMessage>
)

internal data class IndexedMessage(
    val eventId: String,
    val id: Long,
    val conversationId: String,
    val ordinal: Long,
    val role: String,
    val content: String,
    val kind: String,
    val turnId: String?,
    val toolCallId: String?,
    val toolName: String?,
    val toolArgumentsJson: String?,
    val isError: Boolean,
    val reasoningText: String?,
    val reasoningSummary: String?,
    val encryptedReasoning: String?,
    val includeInPrompt: Boolean,
    val searchable: Boolean,
    val createdAtEpochMs: Long
)

internal data class ConversationIndexSnapshot(
    val workspaces: List<IndexedWorkspace>,
    val conversations: List<IndexedConversation>
)

internal interface ConversationIndex {
    suspend fun replaceFrom(snapshot: ConversationIndexSnapshot)
    suspend fun activeWorkspaceId(): String?
    suspend fun setActiveWorkspaceId(id: String?)
    suspend fun activeConversationId(workspaceId: String): String?
    suspend fun setActiveConversationId(workspaceId: String, id: String?)
    fun observeWorkspaces(): Flow<List<WorkspaceSummary>>
    fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>>
}

@Entity(tableName = "workspaces")
internal data class WorkspaceCacheEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val folderName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deleted: Boolean
)

@Entity(tableName = "conversations")
internal data class ConversationCacheEntity(
    @androidx.room.PrimaryKey val id: String,
    val workspaceId: String,
    val path: String,
    val title: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deleted: Boolean
)

@Entity(tableName = "messages")
internal data class MessageCacheEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val messageId: Long,
    val conversationId: String,
    val ordinal: Long,
    val role: String,
    val content: String,
    val kind: String,
    val turnId: String?,
    val toolCallId: String?,
    val toolName: String?,
    val toolArgumentsJson: String?,
    val isError: Boolean,
    val reasoningText: String?,
    val reasoningSummary: String?,
    val encryptedReasoning: String?,
    val includeInPrompt: Boolean,
    val searchable: Boolean,
    val createdAtEpochMs: Long
)

@Entity(tableName = "selections")
internal data class SelectionCacheEntity(
    @androidx.room.PrimaryKey val key: String,
    val value: String
)

@Dao
internal interface ConversationIndexDao {
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM conversations") suspend fun clearConversations()
    @Query("DELETE FROM workspaces") suspend fun clearWorkspaces()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWorkspaces(items: List<WorkspaceCacheEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConversations(items: List<ConversationCacheEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessages(items: List<MessageCacheEntity>)
    @Query("SELECT value FROM selections WHERE `key` = :key") suspend fun selection(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSelection(item: SelectionCacheEntity)
    @Query("DELETE FROM selections WHERE `key` = :key") suspend fun deleteSelection(key: String)
    @Query("SELECT * FROM workspaces WHERE deleted = 0 ORDER BY createdAtEpochMs")
    fun observeWorkspaces(): Flow<List<WorkspaceCacheEntity>>
    @Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId AND deleted = 0 ORDER BY updatedAtEpochMs DESC")
    fun observeConversations(workspaceId: String): Flow<List<ConversationCacheEntity>>
}

@Database(
    entities = [WorkspaceCacheEntity::class, ConversationCacheEntity::class, MessageCacheEntity::class, SelectionCacheEntity::class],
    version = 1,
    exportSchema = false
)
internal abstract class ConversationIndexDatabase : RoomDatabase() {
    abstract fun dao(): ConversationIndexDao

    companion object {
        @Volatile private var instance: ConversationIndexDatabase? = null

        fun get(context: Context): ConversationIndexDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ConversationIndexDatabase::class.java,
                "conversation-index.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}

internal class RoomConversationIndex(
    private val database: ConversationIndexDatabase
) : ConversationIndex {
    private val dao = database.dao()

    override suspend fun replaceFrom(snapshot: ConversationIndexSnapshot) {
        database.withTransaction {
            dao.clearMessages()
            dao.clearConversations()
            dao.clearWorkspaces()
            dao.insertWorkspaces(snapshot.workspaces.map {
                WorkspaceCacheEntity(it.id, it.name, it.folderName, it.createdAtEpochMs, it.updatedAtEpochMs, it.deleted)
            })
            dao.insertConversations(snapshot.conversations.map {
                ConversationCacheEntity(
                    it.id,
                    it.workspaceId,
                    it.filePath.joinToString("/"),
                    it.title,
                    it.createdAtEpochMs,
                    it.updatedAtEpochMs,
                    it.deleted
                )
            })
            dao.insertMessages(snapshot.conversations.flatMap { conversation ->
                conversation.messages.map { message ->
                    MessageCacheEntity(
                        message.eventId, message.id, message.conversationId, message.ordinal,
                        message.role, message.content, message.kind, message.turnId, message.toolCallId,
                        message.toolName, message.toolArgumentsJson, message.isError, message.reasoningText,
                        message.reasoningSummary, message.encryptedReasoning, message.includeInPrompt,
                        message.searchable, message.createdAtEpochMs
                    )
                }
            })
        }
    }

    override suspend fun activeWorkspaceId(): String? = dao.selection(ACTIVE_WORKSPACE)

    override suspend fun setActiveWorkspaceId(id: String?) = setSelection(ACTIVE_WORKSPACE, id)

    override suspend fun activeConversationId(workspaceId: String): String? =
        dao.selection(activeConversationKey(workspaceId))

    override suspend fun setActiveConversationId(workspaceId: String, id: String?) =
        setSelection(activeConversationKey(workspaceId), id)

    override fun observeWorkspaces(): Flow<List<WorkspaceSummary>> = dao.observeWorkspaces().map { rows ->
        rows.map { WorkspaceSummary(it.id, it.name) }
    }

    override fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>> =
        dao.observeConversations(workspaceId).map { rows ->
            rows.map { ConversationSummary(it.id, it.title, it.updatedAtEpochMs) }
        }

    private suspend fun setSelection(key: String, value: String?) {
        if (value == null) dao.deleteSelection(key) else dao.putSelection(SelectionCacheEntity(key, value))
    }

    private fun activeConversationKey(workspaceId: String) = "active-conversation:$workspaceId"

    private companion object {
        const val ACTIVE_WORKSPACE = "active-workspace"
    }
}

internal class MemoryConversationIndex : ConversationIndex {
    private val workspaceFlow = MutableStateFlow<List<WorkspaceSummary>>(emptyList())
    private val conversationFlows = mutableMapOf<String, MutableStateFlow<List<ConversationSummary>>>()
    private var activeWorkspace: String? = null
    private val activeConversations = mutableMapOf<String, String>()

    override suspend fun replaceFrom(snapshot: ConversationIndexSnapshot) {
        workspaceFlow.value = snapshot.workspaces.filterNot { it.deleted }.map { WorkspaceSummary(it.id, it.name) }
        snapshot.workspaces.forEach { workspace ->
            conversationFlows.getOrPut(workspace.id) { MutableStateFlow(emptyList()) }.value =
                snapshot.conversations.filter { it.workspaceId == workspace.id && !it.deleted }
                    .sortedByDescending { it.updatedAtEpochMs }
                    .map { ConversationSummary(it.id, it.title, it.updatedAtEpochMs) }
        }
    }

    override suspend fun activeWorkspaceId(): String? = activeWorkspace
    override suspend fun setActiveWorkspaceId(id: String?) { activeWorkspace = id }
    override suspend fun activeConversationId(workspaceId: String): String? = activeConversations[workspaceId]
    override suspend fun setActiveConversationId(workspaceId: String, id: String?) {
        if (id == null) activeConversations.remove(workspaceId) else activeConversations[workspaceId] = id
    }
    override fun observeWorkspaces(): Flow<List<WorkspaceSummary>> = workspaceFlow
    override fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>> =
        conversationFlows.getOrPut(workspaceId) { MutableStateFlow(emptyList()) }
}
