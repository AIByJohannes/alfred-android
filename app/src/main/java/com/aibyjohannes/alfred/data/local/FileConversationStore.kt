package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class FileConversationStore private constructor(
    private val storage: ChatHistoryStorage,
    private val objectMapper: ObjectMapper
) : ConversationStore {
    constructor(rootDir: File) : this(FileChatHistoryStorage(rootDir), ObjectMapper())
    constructor(storage: ChatHistoryStorage) : this(storage, ObjectMapper())

    private val lock = Any()

    override suspend fun listWorkspaces(): List<WorkspaceSummary> = withStoreLock {
        readState().workspaces.map { WorkspaceSummary(it.id, it.name) }
    }

    override suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary = withStoreLock {
        val state = readState()
        val active = findActiveWorkspace(state)
        if (state.activeWorkspaceId != active.id) {
            state.activeWorkspaceId = active.id
            writeState(state)
        }
        WorkspaceSummary(active.id, active.name)
    }

    override suspend fun createWorkspace(name: String): WorkspaceSummary = withStoreLock {
        val state = readState()
        val newId = state.nextWorkspaceId++
        val workspace = WorkspaceRecord().apply {
            id = newId
            this.name = name
            folderName = uniqueWorkspaceFolderName(state, newId, name)
            createdAtEpochMs = System.currentTimeMillis()
        }
        state.workspaces.add(workspace)
        state.activeWorkspaceId = newId
        storage.ensureDirectory(listOf(workspace.folderName))
        writeState(state)
        WorkspaceSummary(newId, name)
    }

    override suspend fun switchActiveWorkspace(workspaceId: Long): WorkspaceSummary = withStoreLock {
        val state = readState()
        val target = state.workspaces.firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        state.activeWorkspaceId = workspaceId
        writeState(state)
        WorkspaceSummary(target.id, target.name)
    }

    override suspend fun renameWorkspace(workspaceId: Long, newName: String): Unit = withStoreLock {
        val state = readState()
        val target = state.workspaces.firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        target.name = newName
        writeState(state)
    }

    override suspend fun deleteWorkspace(workspaceId: Long): Unit = withStoreLock {
        val state = readState()
        if (state.workspaces.size <= 1) {
            return@withStoreLock
        }
        val workspace = state.workspaces.firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        state.workspaces.removeAll { it.id == workspaceId }
        state.conversations.removeAll { it.workspaceId == workspaceId }
        state.activeConversationIdsByWorkspace.remove(workspaceKey(workspaceId))
        storage.delete(listOf(workspace.folderName))

        if (state.activeWorkspaceId == workspaceId) {
            state.activeWorkspaceId = state.workspaces.firstOrNull()?.id
        }
        writeState(state)
    }

    override suspend fun getOrCreateActiveConversation(): ConversationSummary = withStoreLock {
        val state = readState()
        val activeWorkspace = findActiveWorkspace(state)
        val activeConversationId = state.activeConversationIdsByWorkspace[workspaceKey(activeWorkspace.id)]
        val active = activeConversationId?.let { id ->
            state.conversations.firstOrNull { it.id == id && it.workspaceId == activeWorkspace.id }
        }

        val target = active
            ?: state.conversations
                .filter { it.workspaceId == activeWorkspace.id }
                .maxByOrNull { it.updatedAtEpochMs }
            ?: return@withStoreLock createConversationInternal(state, activeWorkspace)

        state.activeConversationIdsByWorkspace[workspaceKey(activeWorkspace.id)] = target.id
        writeState(state)
        target.toSummary()
    }

    override suspend fun listConversations(): List<ConversationSummary> = withStoreLock {
        val state = readState()
        val activeWorkspace = findActiveWorkspace(state)
        state.conversations
            .filter { it.workspaceId == activeWorkspace.id }
            .sortedByDescending { it.updatedAtEpochMs }
            .map { it.toSummary() }
    }

    override suspend fun createConversation(): ConversationSummary = withStoreLock {
        val state = readState()
        val activeWorkspace = findActiveWorkspace(state)
        createConversationInternal(state, activeWorkspace)
    }

    override suspend fun switchActiveConversation(conversationId: Long): ConversationSummary = withStoreLock {
        val state = readState()
        val activeWorkspace = findActiveWorkspace(state)
        val conversation = state.conversations.firstOrNull {
            it.id == conversationId && it.workspaceId == activeWorkspace.id
        } ?: throw IllegalArgumentException("Conversation not found in active workspace: $conversationId")
        state.activeConversationIdsByWorkspace[workspaceKey(activeWorkspace.id)] = conversationId
        writeState(state)
        conversation.toSummary()
    }

    override suspend fun loadMessages(conversationId: Long): List<StoredChatMessage> = withStoreLock {
        val state = readState()
        val conversation = state.conversations.firstOrNull { it.id == conversationId }
            ?: return@withStoreLock emptyList()
        readMessages(conversation).map {
            StoredChatMessage(
                id = it.id,
                role = it.role,
                content = it.content,
                kind = it.kind,
                turnId = it.turnId,
                toolCallId = it.toolCallId,
                toolName = it.toolName,
                toolArgumentsJson = it.toolArgumentsJson,
                isError = it.isError,
                reasoningText = it.reasoningText,
                reasoningSummary = it.reasoningSummary,
                encryptedReasoning = it.encryptedReasoning,
                includeInPrompt = it.includeInPrompt,
                searchable = it.searchable
            )
        }
    }

    override suspend fun appendMessage(conversationId: Long, role: String, content: String): Unit = withStoreLock {
        appendMessagesInternal(
            conversationId = conversationId,
            drafts = listOf(ConversationMessageDraft(role = role, content = content))
        )
    }

    override suspend fun appendMessages(conversationId: Long, messages: List<ConversationMessageDraft>): Unit = withStoreLock {
        appendMessagesInternal(conversationId, messages)
    }

    private fun appendMessagesInternal(conversationId: Long, drafts: List<ConversationMessageDraft>) {
        if (drafts.isEmpty()) {
            return
        }

        val state = readState()
        val conversation = state.conversations.firstOrNull { it.id == conversationId }
            ?: throw IllegalArgumentException("Conversation not found: $conversationId")
        val now = System.currentTimeMillis()
        drafts.forEach { draft ->
            val message = MessageRecord().apply {
                id = state.nextMessageId++
                this.conversationId = conversationId
                role = draft.role
                content = draft.content
                kind = draft.kind
                turnId = draft.turnId
                toolCallId = draft.toolCallId
                toolName = draft.toolName
                toolArgumentsJson = draft.toolArgumentsJson
                isError = draft.isError
                reasoningText = draft.reasoningText
                reasoningSummary = draft.reasoningSummary
                encryptedReasoning = draft.encryptedReasoning
                includeInPrompt = draft.includeInPrompt
                searchable = draft.searchable
                createdAtEpochMs = now
            }
            storage.appendLine(messagePath(conversation), objectMapper.writeValueAsString(message), JSONL_MIME_TYPE)
            if (draft.role == ChatMessage.ROLE_USER && conversation.title.isNullOrBlank()) {
                conversation.title = buildConversationTitle(draft.content)
            }
        }
        conversation.updatedAtEpochMs = now
        writeState(state)
    }

    override suspend fun deleteConversation(conversationId: Long): Unit = withStoreLock {
        val state = readState()
        val conversation = state.conversations.firstOrNull { it.id == conversationId }
            ?: return@withStoreLock
        state.conversations.removeAll { it.id == conversationId }
        val workspaceKey = workspaceKey(conversation.workspaceId)
        if (state.activeConversationIdsByWorkspace[workspaceKey] == conversationId) {
            state.activeConversationIdsByWorkspace.remove(workspaceKey)
        }
        storage.delete(messagePath(conversation))
        writeState(state)
    }

    suspend fun searchSessionMessages(query: String, limit: Int): List<SessionSearchHit> = withStoreLock {
        val terms = normalizeTerms(query)
        if (terms.isEmpty()) {
            return@withStoreLock emptyList()
        }

        val state = readState()
        val conversationsById = state.conversations.associateBy { it.id }
        state.conversations.flatMap { conversation ->
            readMessages(conversation).filter { it.searchable }.mapNotNull { message ->
                val score = score(message.content, terms)
                if (score <= 0) {
                    null
                } else {
                    SessionSearchHit(
                        conversationId = conversation.id,
                        messageId = message.id,
                        title = conversation.title?.takeIf { it.isNotBlank() } ?: "Conversation ${conversation.id}",
                        snippet = buildSnippet(message.content, terms),
                        timestampEpochMs = message.createdAtEpochMs,
                        score = score
                    )
                }
            }
        }.sortedWith(
            compareByDescending<SessionSearchHit> { it.score }
                .thenByDescending { it.timestampEpochMs }
                .thenBy { conversationsById[it.conversationId]?.id ?: it.conversationId }
        ).take(limit)
    }

    private suspend fun <T> withStoreLock(block: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            storage.ensureReady()
            block()
        }
    }

    private fun createConversationInternal(state: StoreState, workspace: WorkspaceRecord): ConversationSummary {
        val now = System.currentTimeMillis()
        val id = state.nextConversationId++
        val conversation = ConversationRecord().apply {
            this.id = id
            title = null
            createdAtEpochMs = now
            updatedAtEpochMs = now
            workspaceId = workspace.id
            workspaceFolderName = workspace.folderName
            fileName = "conversation-$id.jsonl"
        }
        state.activeConversationIdsByWorkspace[workspaceKey(workspace.id)] = id
        state.conversations.add(conversation)
        storage.writeText(messagePath(conversation), "", JSONL_MIME_TYPE)
        writeState(state)
        return conversation.toSummary()
    }

    private fun readState(): StoreState {
        val raw = storage.readText(METADATA_PATH)
        val state = if (raw.isNullOrBlank()) {
            StoreState()
        } else {
            objectMapper.readValue(raw, StoreState::class.java)
        }
        val changed = normalizeState(state)
        if (changed || raw.isNullOrBlank()) {
            writeState(state)
        }
        return state
    }

    private fun normalizeState(state: StoreState): Boolean {
        var changed = false
        if (state.workspaces.isEmpty()) {
            state.workspaces.add(
                WorkspaceRecord().apply {
                    id = 1L
                    name = "Personal"
                    folderName = "workspace-1-personal"
                    createdAtEpochMs = System.currentTimeMillis()
                }
            )
            state.activeWorkspaceId = 1L
            if (state.nextWorkspaceId <= 1L) {
                state.nextWorkspaceId = 2L
            }
            changed = true
        }

        for (workspace in state.workspaces) {
            if (workspace.folderName.isBlank()) {
                workspace.folderName = uniqueWorkspaceFolderName(state, workspace.id, workspace.name)
                changed = true
            }
            storage.ensureDirectory(listOf(workspace.folderName))
        }

        if (state.activeWorkspaceId == null || state.workspaces.none { it.id == state.activeWorkspaceId }) {
            state.activeWorkspaceId = state.workspaces.first().id
            changed = true
        }

        for (conversation in state.conversations) {
            if (conversation.workspaceId == 0L) {
                conversation.workspaceId = 1L
                changed = true
            }
            val workspace = state.workspaces.firstOrNull { it.id == conversation.workspaceId }
            if (workspace != null && conversation.workspaceFolderName != workspace.folderName) {
                conversation.workspaceFolderName = workspace.folderName
                changed = true
            }
            if (conversation.fileName.isBlank()) {
                conversation.fileName = "${conversation.id}.jsonl"
                changed = true
            }
        }

        state.activeConversationId?.let { legacyActiveId ->
            val legacyConversation = state.conversations.firstOrNull { it.id == legacyActiveId }
            if (legacyConversation != null) {
                state.activeConversationIdsByWorkspace[workspaceKey(legacyConversation.workspaceId)] = legacyActiveId
                changed = true
            }
            state.activeConversationId = null
        }

        return changed
    }

    private fun findActiveWorkspace(state: StoreState): WorkspaceRecord {
        val activeId = state.activeWorkspaceId ?: state.workspaces.firstOrNull()?.id
        return state.workspaces.firstOrNull { it.id == activeId }
            ?: state.workspaces.firstOrNull()
            ?: throw IllegalStateException("No workspaces found")
    }

    private fun writeState(state: StoreState) {
        storage.writeText(
            METADATA_PATH,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
            JSON_MIME_TYPE
        )
    }

    private fun readMessages(conversation: ConversationRecord): List<MessageRecord> {
        val raw = storage.readText(messagePath(conversation)) ?: return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { objectMapper.readValue(it, MessageRecord::class.java) }
            .toList()
    }

    private fun messagePath(conversation: ConversationRecord): List<String> {
        return listOf(conversation.workspaceFolderName, conversation.fileName)
    }

    private fun ConversationRecord.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }

    private fun uniqueWorkspaceFolderName(state: StoreState, workspaceId: Long, name: String): String {
        val base = "workspace-$workspaceId-${slugify(name).ifBlank { "workspace" }}"
        val existing = state.workspaces
            .filterNot { it.id == workspaceId }
            .map { it.folderName }
            .toSet()
        if (base !in existing) {
            return base
        }
        var suffix = 2
        while ("$base-$suffix" in existing) {
            suffix++
        }
        return "$base-$suffix"
    }

    private fun slugify(name: String): String {
        return name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
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

    private fun normalizeTerms(query: String): List<String> {
        return query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun score(content: String, terms: List<String>): Int {
        val normalized = content.lowercase()
        return terms.sumOf { term ->
            Regex("\\b${Regex.escape(term)}\\b").findAll(normalized).count()
        }
    }

    private fun buildSnippet(content: String, terms: List<String>): String {
        val lower = content.lowercase()
        val firstMatch = terms.mapNotNull { lower.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull() ?: 0
        val start = (firstMatch - 80).coerceAtLeast(0)
        val end = (firstMatch + 220).coerceAtMost(content.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < content.length) "..." else ""
        return prefix + content.substring(start, end).trim() + suffix
    }

    private fun workspaceKey(workspaceId: Long): String = workspaceId.toString()

    private class StoreState {
        var activeWorkspaceId: Long? = null
        var nextWorkspaceId: Long = 1L
        var activeConversationId: Long? = null
        var activeConversationIdsByWorkspace: MutableMap<String, Long> = mutableMapOf()
        var nextConversationId: Long = 1L
        var nextMessageId: Long = 1L
        var conversations: MutableList<ConversationRecord> = mutableListOf()
        var workspaces: MutableList<WorkspaceRecord> = mutableListOf()
    }

    private class WorkspaceRecord {
        var id: Long = 0L
        var name: String = ""
        var folderName: String = ""
        var createdAtEpochMs: Long = 0L
    }

    private class ConversationRecord {
        var id: Long = 0L
        var title: String? = null
        var createdAtEpochMs: Long = 0L
        var updatedAtEpochMs: Long = 0L
        var workspaceId: Long = 1L
        var workspaceFolderName: String = ""
        var fileName: String = ""
    }

    private class MessageRecord {
        var id: Long = 0L
        var conversationId: Long = 0L
        var role: String = ""
        var content: String = ""
        var kind: String = ChatMessage.KIND_MESSAGE
        var turnId: String? = null
        var toolCallId: String? = null
        var toolName: String? = null
        var toolArgumentsJson: String? = null
        var isError: Boolean = false
        var reasoningText: String? = null
        var reasoningSummary: String? = null
        var encryptedReasoning: String? = null
        var includeInPrompt: Boolean = true
        var searchable: Boolean = true
        var createdAtEpochMs: Long = 0L
    }

    companion object {
        private val METADATA_PATH = listOf("metadata.json")
        private const val JSON_MIME_TYPE = "application/json"
        private const val JSONL_MIME_TYPE = "application/x-ndjson"
    }
}

data class SessionSearchHit(
    val conversationId: Long,
    val messageId: Long,
    val title: String,
    val snippet: String,
    val timestampEpochMs: Long,
    val score: Int
)
