package com.aibyjohannes.alfred.data.local

import android.content.Context
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class FileConversationStore internal constructor(
    private val storage: ChatHistoryStorage,
    private val objectMapper: ObjectMapper,
    private val index: ConversationIndex
) : ConversationStore {
    constructor(rootDir: File) : this(FileChatHistoryStorage(rootDir), ObjectMapper(), MemoryConversationIndex())
    constructor(storage: ChatHistoryStorage) : this(storage, ObjectMapper(), MemoryConversationIndex())
    constructor(storage: ChatHistoryStorage, context: Context) : this(
        storage,
        ObjectMapper(),
        RoomConversationIndex(ConversationIndexDatabase.get(context))
    )

    override suspend fun listWorkspaces(): List<WorkspaceSummary> = withStoreLock {
        loadSnapshot().activeWorkspaces().map { WorkspaceSummary(it.id, it.name) }
    }

    override fun observeWorkspaces(): Flow<List<WorkspaceSummary>> = index.observeWorkspaces()

    override suspend fun getOrCreateActiveWorkspace(): WorkspaceSummary = withStoreLock {
        val snapshot = ensureWorkspace(loadSnapshot())
        val active = resolveActiveWorkspace(snapshot)
        index.setActiveWorkspaceId(active.id)
        WorkspaceSummary(active.id, active.name)
    }

    override suspend fun createWorkspace(name: String): WorkspaceSummary = withStoreLock {
        require(name.isNotBlank()) { "Workspace name must not be blank" }
        val id = UUID.randomUUID().toString()
        val folderName = "workspace-$id-${slugify(name)}"
        storage.ensureDirectory(listOf(folderName))
        val path = listOf(folderName, WORKSPACE_FILE)
        storage.createFileExclusive(path, JSONL_MIME_TYPE)
        appendEvent(path, workspaceEvent(WORKSPACE_CREATED, id, name))
        val snapshot = refreshIndex()
        check(snapshot.workspaces.any { it.id == id && !it.deleted }) { "Workspace was not recoverable after creation" }
        index.setActiveWorkspaceId(id)
        WorkspaceSummary(id, name)
    }

    override suspend fun switchActiveWorkspace(workspaceId: String): WorkspaceSummary = withStoreLock {
        val workspace = loadSnapshot().activeWorkspaces().firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        index.setActiveWorkspaceId(workspace.id)
        WorkspaceSummary(workspace.id, workspace.name)
    }

    override suspend fun renameWorkspace(workspaceId: String, newName: String): Unit = withStoreLock {
        require(newName.isNotBlank()) { "Workspace name must not be blank" }
        val workspace = loadSnapshot().activeWorkspaces().firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        appendEvent(
            listOf(workspace.folderName, WORKSPACE_FILE),
            workspaceEvent(WORKSPACE_RENAMED, workspaceId, newName)
        )
        refreshIndex()
    }

    override suspend fun deleteWorkspace(workspaceId: String): Unit = withStoreLock {
        val snapshot = loadSnapshot()
        if (snapshot.activeWorkspaces().size <= 1) return@withStoreLock
        val workspace = snapshot.activeWorkspaces().firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        appendEvent(
            listOf(workspace.folderName, WORKSPACE_FILE),
            workspaceEvent(WORKSPACE_DELETED, workspaceId, workspace.name)
        )
        if (index.activeWorkspaceId() == workspaceId) index.setActiveWorkspaceId(null)
        refreshIndex()
    }

    override suspend fun getOrCreateActiveConversation(): ConversationSummary = withStoreLock {
        val snapshot = ensureWorkspace(loadSnapshot())
        val workspace = resolveActiveWorkspace(snapshot)
        val candidates = snapshot.activeConversations(workspace.id)
        val activeId = index.activeConversationId(workspace.id)
        val active = candidates.firstOrNull { it.id == activeId }
            ?: candidates.maxByOrNull { it.updatedAtEpochMs }
        if (active != null) {
            index.setActiveConversationId(workspace.id, active.id)
            return@withStoreLock active.toSummary()
        }
        createConversationInternal(workspace)
    }

    override suspend fun listConversations(): List<ConversationSummary> = withStoreLock {
        val snapshot = ensureWorkspace(loadSnapshot())
        val workspace = resolveActiveWorkspace(snapshot)
        snapshot.activeConversations(workspace.id)
            .sortedByDescending { it.updatedAtEpochMs }
            .map { it.toSummary() }
    }

    override fun observeConversations(workspaceId: String): Flow<List<ConversationSummary>> =
        index.observeConversations(workspaceId)

    override suspend fun createConversation(): ConversationSummary = withStoreLock {
        val snapshot = ensureWorkspace(loadSnapshot())
        createConversationInternal(resolveActiveWorkspace(snapshot))
    }

    override suspend fun switchActiveConversation(conversationId: String): ConversationSummary = withStoreLock {
        val snapshot = ensureWorkspace(loadSnapshot())
        val workspace = resolveActiveWorkspace(snapshot)
        val conversation = snapshot.activeConversations(workspace.id).firstOrNull { it.id == conversationId }
            ?: throw IllegalArgumentException("Conversation not found in active workspace: $conversationId")
        index.setActiveConversationId(workspace.id, conversation.id)
        conversation.toSummary()
    }

    override suspend fun loadMessages(conversationId: String): List<StoredChatMessage> = withStoreLock {
        loadSnapshot().conversations.firstOrNull { it.id == conversationId && !it.deleted }
            ?.messages
            ?.sortedBy { it.ordinal }
            ?.map { it.toStoredMessage() }
            .orEmpty()
    }

    override suspend fun appendMessage(conversationId: String, role: String, content: String) {
        appendMessages(conversationId, listOf(ConversationMessageDraft(role = role, content = content)))
    }

    override suspend fun appendMessages(conversationId: String, messages: List<ConversationMessageDraft>): Unit =
        withStoreLock {
            if (messages.isEmpty()) return@withStoreLock
            val conversation = loadSnapshot().conversations.firstOrNull { it.id == conversationId && !it.deleted }
                ?: throw IllegalArgumentException("Conversation not found: $conversationId")
            messages.forEach { draft -> appendEvent(conversation.filePath, messageEvent(conversationId, draft)) }
            if (conversation.title.isNullOrBlank()) {
                messages.firstOrNull { it.role == ChatMessage.ROLE_USER }?.let { firstUser ->
                    appendEvent(conversation.filePath, conversationTitleEvent(conversationId, buildConversationTitle(firstUser.content)))
                }
            }
            refreshIndex()
        }

    override suspend fun deleteConversation(conversationId: String): Unit = withStoreLock {
        val conversation = loadSnapshot().conversations.firstOrNull { it.id == conversationId && !it.deleted }
            ?: return@withStoreLock
        appendEvent(conversation.filePath, baseEvent(CONVERSATION_DELETED).apply {
            put("conversationId", conversationId)
        })
        if (index.activeConversationId(conversation.workspaceId) == conversationId) {
            index.setActiveConversationId(conversation.workspaceId, null)
        }
        refreshIndex()
    }

    override suspend fun restoreConversation(conversationId: String): Unit = withStoreLock {
        // Find the conversation where it is currently deleted
        val conversation = loadSnapshot().conversations.firstOrNull { it.id == conversationId && it.deleted }
            ?: return@withStoreLock
        appendEvent(conversation.filePath, baseEvent(CONVERSATION_RESTORED).apply {
            put("conversationId", conversationId)
            put("timestampEpochMs", System.currentTimeMillis())
        })
        refreshIndex()
    }

    suspend fun searchSessionMessages(query: String, limit: Int): List<SessionSearchHit> = withStoreLock {
        val terms = normalizeTerms(query)
        if (terms.isEmpty()) return@withStoreLock emptyList()
        loadSnapshot().conversations.filterNot { it.deleted }.flatMap { conversation ->
            conversation.messages.filter { it.searchable }.mapNotNull { message ->
                val score = score(message.content, terms)
                if (score <= 0) null else SessionSearchHit(
                    conversationId = conversation.id,
                    messageId = message.id,
                    title = conversation.title?.takeIf { it.isNotBlank() } ?: "Conversation ${conversation.id}",
                    snippet = buildSnippet(message.content, terms),
                    timestampEpochMs = message.createdAtEpochMs,
                    score = score
                )
            }
        }.sortedWith(compareByDescending<SessionSearchHit> { it.score }.thenByDescending { it.timestampEpochMs })
            .take(limit)
    }

    suspend fun rebuildIndexFromFiles(): Unit = withStoreLock {
        refreshIndex()
        Unit
    }

    private suspend fun createConversationInternal(workspace: IndexedWorkspace): ConversationSummary {
        repeat(8) {
            val id = UUID.randomUUID().toString()
            val path = listOf(workspace.folderName, "conversation-$id.jsonl")
            if (storage.readText(path) !is StorageReadResult.Missing) return@repeat
            storage.createFileExclusive(path, JSONL_MIME_TYPE)
            appendEvent(path, conversationCreatedEvent(id, workspace.id, System.currentTimeMillis()))
            val snapshot = refreshIndex()
            val created = snapshot.conversations.firstOrNull { it.id == id && !it.deleted }
                ?: throw IllegalStateException("Conversation was not recoverable after creation")
            index.setActiveConversationId(workspace.id, id)
            return created.toSummary()
        }
        throw IllegalStateException("Could not allocate a unique conversation ID")
    }

    private suspend fun resolveActiveWorkspace(snapshot: ConversationIndexSnapshot): IndexedWorkspace {
        val workspaces = snapshot.activeWorkspaces()
        val selected = index.activeWorkspaceId()?.let { id -> workspaces.firstOrNull { it.id == id } }
        return selected ?: workspaces.firstOrNull()
            ?: throw IllegalStateException("No workspaces found")
    }

    private suspend fun ensureWorkspace(snapshot: ConversationIndexSnapshot): ConversationIndexSnapshot {
        if (snapshot.activeWorkspaces().isNotEmpty()) return snapshot
        val id = UUID.randomUUID().toString()
        val folderName = "workspace-$id-personal"
        storage.ensureDirectory(listOf(folderName))
        val path = listOf(folderName, WORKSPACE_FILE)
        storage.createFileExclusive(path, JSONL_MIME_TYPE)
        appendEvent(path, workspaceEvent(WORKSPACE_CREATED, id, "Personal"))
        return refreshIndex().also { index.setActiveWorkspaceId(id) }
    }

    private suspend fun loadSnapshot(): ConversationIndexSnapshot {
        val legacySelections = migrateLegacyStorage()
        val snapshot = refreshIndex()
        if (legacySelections != null) {
            legacySelections.activeWorkspaceId
                ?.takeIf { id -> snapshot.activeWorkspaces().any { it.id == id } }
                ?.let { index.setActiveWorkspaceId(it) }
            legacySelections.activeConversationIds.forEach { (workspaceId, conversationId) ->
                if (snapshot.activeConversations(workspaceId).any { it.id == conversationId }) {
                    index.setActiveConversationId(workspaceId, conversationId)
                }
            }
            // The converted files and rebuilt Room cache have both verified successfully.
            storage.delete(METADATA_PATH)
            storage.delete(METADATA_BACKUP_PATH)
        }
        return snapshot
    }

    private suspend fun refreshIndex(): ConversationIndexSnapshot {
        val snapshot = scanEventFiles()
        index.replaceFrom(snapshot)
        return snapshot
    }

    /**
     * JSONL is authoritative. This scan folds every entity log and then replaces the Room read model.
     * A Room failure cannot roll back or invalidate a successfully verified file append.
     */
    private fun scanEventFiles(): ConversationIndexSnapshot {
        val workspaces = mutableListOf<IndexedWorkspace>()
        val conversations = mutableListOf<IndexedConversation>()
        val folders = storage.listChildren(emptyList()).filter { it.isDirectory && it.name.startsWith("workspace-") }
        folders.forEach { folder ->
            val manifestPath = listOf(folder.name, WORKSPACE_FILE)
            val manifest = (storage.readText(manifestPath) as? StorageReadResult.Found)?.text ?: return@forEach
            foldWorkspace(manifest, folder.name)?.let(workspaces::add)
            storage.listChildren(listOf(folder.name))
                .filter { !it.isDirectory && CONVERSATION_FILE_REGEX.matches(it.name) }
                .forEach { entry ->
                    val path = listOf(folder.name, entry.name)
                    val raw = (storage.readText(path) as? StorageReadResult.Found)?.text ?: return@forEach
                    foldConversation(raw, path)?.let(conversations::add)
                }
        }
        conversations.map { it.workspaceId }.distinct().filter { workspaceId -> workspaces.none { it.id == workspaceId } }
            .forEach { missingId ->
                workspaces += IndexedWorkspace(
                    missingId, "Recovered Workspace", "", 0L,
                    conversations.filter { it.workspaceId == missingId }.maxOfOrNull { it.updatedAtEpochMs } ?: 0L,
                    false
                )
            }
        return ConversationIndexSnapshot(workspaces.distinctBy { it.id }, conversations.distinctBy { it.id })
    }

    private fun foldWorkspace(raw: String, folderName: String): IndexedWorkspace? {
        var id: String? = null
        var name: String? = null
        var createdAt = 0L
        var updatedAt = 0L
        var deleted = false
        parseJsonLines(raw).forEach { event ->
            val eventType = event.path("eventType").asText()
            if (eventType !in setOf(WORKSPACE_CREATED, WORKSPACE_RENAMED, WORKSPACE_DELETED)) return@forEach
            id = event.path("workspaceId").asText(id ?: "")
            name = event.path("name").asText(name ?: "Workspace")
            val timestamp = event.path("createdAtEpochMs").asLong(event.path("timestampEpochMs").asLong(0L))
            if (createdAt == 0L && timestamp > 0L) createdAt = timestamp
            updatedAt = maxOf(updatedAt, timestamp)
            if (eventType == WORKSPACE_DELETED) deleted = true
        }
        val workspaceId = id?.takeIf { it.isNotBlank() } ?: return null
        return IndexedWorkspace(workspaceId, name ?: "Workspace", folderName, createdAt, updatedAt, deleted)
    }

    private fun foldConversation(raw: String, path: List<String>): IndexedConversation? {
        var id: String? = null
        var workspaceId: String? = null
        var title: String? = null
        var createdAt = 0L
        var updatedAt = 0L
        var deleted = false
        val messages = mutableListOf<IndexedMessage>()
        parseJsonLines(raw).forEachIndexed { ordinal, event ->
            when (event.path("eventType").asText()) {
                CONVERSATION_CREATED -> {
                    id = event.path("conversationId").asText()
                    workspaceId = event.path("workspaceId").asText()
                    title = event.path("title").takeUnless { it.isMissingNode || it.isNull }?.asText()
                    createdAt = event.path("createdAtEpochMs").asLong()
                    updatedAt = maxOf(updatedAt, createdAt)
                }
                MESSAGE_APPENDED, "" -> {
                    if (!event.has("role")) return@forEachIndexed
                    val conversation = id ?: event.path("conversationId").asText().toLegacyId()
                    if (conversation.isBlank()) return@forEachIndexed
                    val timestamp = event.path("createdAtEpochMs").asLong(System.currentTimeMillis())
                    messages += event.toIndexedMessage(conversation, ordinal.toLong(), timestamp)
                    updatedAt = maxOf(updatedAt, timestamp)
                }
                TITLE_CHANGED -> {
                    title = event.path("title").asText()
                    updatedAt = maxOf(updatedAt, event.path("timestampEpochMs").asLong())
                }
                // membership = latest delete/restore event by append order
                CONVERSATION_DELETED -> {
                    deleted = true
                    updatedAt = maxOf(updatedAt, event.path("timestampEpochMs").asLong())
                }
                CONVERSATION_RESTORED -> {
                    deleted = false
                    updatedAt = maxOf(updatedAt, event.path("timestampEpochMs").asLong())
                }
            }
        }
        val conversationId = id?.takeIf { it.isNotBlank() } ?: return null
        val parentId = workspaceId?.takeIf { it.isNotBlank() } ?: return null
        if (title.isNullOrBlank()) title = messages.firstOrNull { it.role == ChatMessage.ROLE_USER }
            ?.content?.let(::buildConversationTitle)
        return IndexedConversation(conversationId, parentId, path, title, createdAt, updatedAt, deleted, messages)
    }

    private fun migrateLegacyStorage(): LegacySelections? {
        val primary = storage.readText(METADATA_PATH)
        val backup = storage.readText(METADATA_BACKUP_PATH)
        val candidates = listOfNotNull(
            (primary as? StorageReadResult.Found)?.text,
            (backup as? StorageReadResult.Found)?.text
        )
        val metadata = candidates.firstOrNull { candidate ->
            candidate.isNotBlank() && runCatching { objectMapper.readTree(candidate) }.isSuccess
        }
        if (candidates.isNotEmpty() && metadata == null) {
            throw IllegalStateException("Chat history metadata and backup are unreadable")
        }
        val selections = metadata?.let(::migrateFromMetadata)
        migrateHeaderlessFiles()
        return selections
    }

    private fun migrateFromMetadata(raw: String): LegacySelections {
        check(raw.isNotBlank()) { "Chat history metadata is empty" }
        val root = objectMapper.readTree(raw)
        val workspaceFolders = mutableMapOf<Long, String>()
        root.path("workspaces").forEach { workspace ->
            val legacyId = workspace.path("id").asLong()
            val id = legacyId.toLegacyId()
            val name = workspace.path("name").asText("Recovered Workspace")
            val folder = workspace.path("folderName").asText("workspace-$legacyId-${slugify(name)}")
            workspaceFolders[legacyId] = folder
            storage.ensureDirectory(listOf(folder))
            ensureWorkspaceManifest(folder, id, name, workspace.path("createdAtEpochMs").asLong(System.currentTimeMillis()))
        }
        root.path("conversations").forEach { conversation ->
            val legacyId = conversation.path("id").asLong()
            val legacyWorkspaceId = conversation.path("workspaceId").asLong(1L)
            val workspaceId = legacyWorkspaceId.toLegacyId()
            val folder = conversation.path("workspaceFolderName").asText(
                workspaceFolders[legacyWorkspaceId] ?: "workspace-$legacyWorkspaceId-personal"
            )
            storage.ensureDirectory(listOf(folder))
            ensureWorkspaceManifest(folder, workspaceId, "Recovered Workspace", conversation.path("createdAtEpochMs").asLong())
            val fileName = conversation.path("fileName").asText("conversation-$legacyId.jsonl")
            ensureConversationHeader(
                listOf(folder, fileName), legacyId.toLegacyId(), workspaceId,
                conversation.path("title").takeUnless { it.isMissingNode || it.isNull }?.asText(),
                conversation.path("createdAtEpochMs").asLong(System.currentTimeMillis())
            )
        }
        check(scanEventFiles().workspaces.isNotEmpty()) { "Legacy migration produced no recoverable workspaces" }
        val activeWorkspaceId = root.path("activeWorkspaceId").takeUnless { it.isMissingNode || it.isNull }
            ?.asLong()?.toLegacyId()
        val activeConversations = root.path("activeConversationIdsByWorkspace").fields().asSequence()
            .associate { (workspaceId, conversationId) ->
                workspaceId.toLongOrNull()?.toLegacyId().orEmpty() to conversationId.asLong().toLegacyId()
            }.filterKeys { it.isNotBlank() }
        val legacyActive = root.path("activeConversationId").takeUnless { it.isMissingNode || it.isNull }
            ?.asLong()?.toLegacyId()
        val mergedSelections = if (legacyActive != null && activeWorkspaceId != null) {
            activeConversations + (activeWorkspaceId to legacyActive)
        } else {
            activeConversations
        }
        return LegacySelections(activeWorkspaceId, mergedSelections)
    }

    private fun migrateHeaderlessFiles() {
        storage.listChildren(emptyList()).filter { it.isDirectory && it.name.startsWith("workspace-") }.forEach { folder ->
            val numericWorkspaceId = WORKSPACE_LEGACY_REGEX.find(folder.name)?.groupValues?.get(1)?.toLongOrNull()
            val workspaceId = numericWorkspaceId?.toLegacyId() ?: return@forEach
            val inferredName = folder.name.substringAfter("workspace-${numericWorkspaceId}-", "Recovered Workspace")
                .split('-').joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
            ensureWorkspaceManifest(folder.name, workspaceId, inferredName, System.currentTimeMillis())
            storage.listChildren(listOf(folder.name)).filter { !it.isDirectory }.forEach { entry ->
                val legacyConversationId = CONVERSATION_LEGACY_REGEX.matchEntire(entry.name)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                ensureConversationHeader(
                    listOf(folder.name, entry.name), legacyConversationId.toLegacyId(), workspaceId,
                    null, System.currentTimeMillis()
                )
            }
        }
    }

    private fun ensureWorkspaceManifest(folder: String, id: String, name: String, createdAt: Long) {
        val path = listOf(folder, WORKSPACE_FILE)
        when (val result = storage.readText(path)) {
            StorageReadResult.Missing -> {
                storage.createFileExclusive(path, JSONL_MIME_TYPE)
                appendEvent(path, workspaceEvent(WORKSPACE_CREATED, id, name, createdAt))
            }
            is StorageReadResult.Found -> check(foldWorkspace(result.text, folder) != null) {
                "Workspace manifest is invalid: $folder/$WORKSPACE_FILE"
            }
        }
    }

    private fun ensureConversationHeader(
        path: List<String>, id: String, workspaceId: String, title: String?, createdAt: Long
    ) {
        val result = storage.readText(path)
        val existing = (result as? StorageReadResult.Found)?.text.orEmpty()
        val first = existing.lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        if (first?.path("eventType")?.asText() == CONVERSATION_CREATED) return
        val header = objectMapper.writeValueAsString(conversationCreatedEvent(id, workspaceId, createdAt, title))
        val migrated = buildString {
            append(header).append('\n')
            if (existing.isNotBlank()) append(existing).also { if (!existing.endsWith("\n")) append('\n') }
        }
        if (result is StorageReadResult.Missing) {
            storage.createFileExclusive(path, JSONL_MIME_TYPE)
        }
        storage.replaceTextVerified(path, migrated, JSONL_MIME_TYPE)
        val verified = (storage.readText(path) as? StorageReadResult.Found)?.text
        check(verified != null && foldConversation(verified, path) != null) {
            "Conversation migration verification failed: ${path.joinToString("/")}"
        }
    }

    private fun parseJsonLines(raw: String): List<JsonNode> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        return buildList {
            lines.forEachIndexed { index, line ->
                val parsed = runCatching { objectMapper.readTree(line) }
                if (parsed.isSuccess) add(parsed.getOrThrow())
                else if (index != lines.lastIndex) throw parsed.exceptionOrNull()!!
            }
        }
    }

    private fun workspaceEvent(type: String, id: String, name: String, timestamp: Long = System.currentTimeMillis()) =
        baseEvent(type).apply {
            put("workspaceId", id)
            put("name", name)
            if (type == WORKSPACE_CREATED) put("createdAtEpochMs", timestamp) else put("timestampEpochMs", timestamp)
        }

    private fun conversationCreatedEvent(
        id: String, workspaceId: String, createdAt: Long, title: String? = null
    ) = baseEvent(CONVERSATION_CREATED).apply {
        put("conversationId", id)
        put("workspaceId", workspaceId)
        if (title == null) putNull("title") else put("title", title)
        put("createdAtEpochMs", createdAt)
    }

    private fun conversationTitleEvent(id: String, title: String) = baseEvent(TITLE_CHANGED).apply {
        put("conversationId", id)
        put("title", title)
        put("timestampEpochMs", System.currentTimeMillis())
    }

    private fun messageEvent(conversationId: String, draft: ConversationMessageDraft) = baseEvent(MESSAGE_APPENDED).apply {
        put("eventId", UUID.randomUUID().toString())
        put("id", randomPositiveLong())
        put("conversationId", conversationId)
        put("role", draft.role)
        put("content", draft.content)
        put("kind", draft.kind)
        putNullable("turnId", draft.turnId)
        putNullable("toolCallId", draft.toolCallId)
        putNullable("toolName", draft.toolName)
        putNullable("toolArgumentsJson", draft.toolArgumentsJson)
        put("isError", draft.isError)
        putNullable("reasoningText", draft.reasoningText)
        putNullable("reasoningSummary", draft.reasoningSummary)
        putNullable("encryptedReasoning", draft.encryptedReasoning)
        put("includeInPrompt", draft.includeInPrompt)
        put("searchable", draft.searchable)
        put("createdAtEpochMs", System.currentTimeMillis())
    }

    private fun baseEvent(type: String): ObjectNode = objectMapper.createObjectNode().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("eventType", type)
    }

    private fun appendEvent(path: List<String>, event: ObjectNode) {
        storage.appendLine(path, objectMapper.writeValueAsString(event), JSONL_MIME_TYPE)
    }

    private suspend fun <T> withStoreLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        PROCESS_WRITER_MUTEX.withLock {
            storage.ensureReady()
            block()
        }
    }

    private fun IndexedConversation.toSummary() = ConversationSummary(id, title, updatedAtEpochMs)

    private fun IndexedMessage.toStoredMessage() = StoredChatMessage(
        id, role, content, kind, turnId, toolCallId, toolName, toolArgumentsJson, isError,
        reasoningText, reasoningSummary, encryptedReasoning, includeInPrompt, searchable
    )

    private fun JsonNode.toIndexedMessage(
        conversationId: String, ordinal: Long, timestamp: Long
    ) = IndexedMessage(
        eventId = path("eventId").asText("legacy-message-${path("id").asLong()}-$ordinal"),
        id = path("id").asLong(randomPositiveLong()),
        conversationId = conversationId,
        ordinal = ordinal,
        role = path("role").asText(),
        content = path("content").asText(),
        kind = path("kind").asText("message"),
        turnId = nullableText("turnId"),
        toolCallId = nullableText("toolCallId"),
        toolName = nullableText("toolName"),
        toolArgumentsJson = nullableText("toolArgumentsJson"),
        isError = path("isError").asBoolean(false),
        reasoningText = nullableText("reasoningText"),
        reasoningSummary = nullableText("reasoningSummary"),
        encryptedReasoning = nullableText("encryptedReasoning"),
        includeInPrompt = path("includeInPrompt").asBoolean(true),
        searchable = path("searchable").asBoolean(true),
        createdAtEpochMs = timestamp
    )

    private fun JsonNode.nullableText(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    private fun ObjectNode.putNullable(field: String, value: String?) {
        if (value == null) putNull(field) else put(field, value)
    }

    private fun Long.toLegacyId() = "legacy-$this"
    private fun String.toLegacyId() = toLongOrNull()?.toLegacyId() ?: this

    private fun ConversationIndexSnapshot.activeWorkspaces() = workspaces.filterNot { it.deleted }
    private fun ConversationIndexSnapshot.activeConversations(workspaceId: String) =
        conversations.filter { it.workspaceId == workspaceId && !it.deleted }

    private fun slugify(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "workspace" }

    private fun buildConversationTitle(content: String): String {
        val normalized = content.trim().replace(Regex("\\s+"), " ")
        return when {
            normalized.isBlank() -> "New conversation"
            normalized.length <= 48 -> normalized
            else -> normalized.take(47).trimEnd() + "…"
        }
    }

    private fun normalizeTerms(query: String): List<String> = query.lowercase(Locale.US)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .distinct()

    private fun score(content: String, terms: List<String>): Int {
        val haystack = content.lowercase(Locale.US)
        return terms.sumOf { term -> Regex(Regex.escape(term)).findAll(haystack).count() }
    }

    private fun buildSnippet(content: String, terms: List<String>): String {
        val index = terms.map { content.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (index - 48).coerceAtLeast(0)
        val end = (start + 180).coerceAtMost(content.length)
        return (if (start > 0) "…" else "") + content.substring(start, end).trim() +
            (if (end < content.length) "…" else "")
    }

    private fun randomPositiveLong(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

    private data class LegacySelections(
        val activeWorkspaceId: String?,
        val activeConversationIds: Map<String, String>
    )

    companion object {
        private val PROCESS_WRITER_MUTEX = Mutex()
        private const val SCHEMA_VERSION = 1
        private const val JSONL_MIME_TYPE = "application/x-ndjson"
        private const val WORKSPACE_FILE = "workspace.jsonl"
        private const val WORKSPACE_CREATED = "workspaceCreated"
        private const val WORKSPACE_RENAMED = "workspaceRenamed"
        private const val WORKSPACE_DELETED = "workspaceDeleted"
        private const val CONVERSATION_CREATED = "conversationCreated"
        private const val MESSAGE_APPENDED = "messageAppended"
        private const val TITLE_CHANGED = "titleChanged"
        private const val CONVERSATION_DELETED = "conversationDeleted"
        private const val CONVERSATION_RESTORED = "conversationRestored"
        private val METADATA_PATH = listOf("metadata.json")
        private val METADATA_BACKUP_PATH = listOf("metadata.backup.json")
        private val CONVERSATION_FILE_REGEX = Regex("^conversation-.+\\.jsonl$")
        private val CONVERSATION_LEGACY_REGEX = Regex("^conversation-(\\d+)\\.jsonl$")
        private val WORKSPACE_LEGACY_REGEX = Regex("^workspace-(\\d+)(?:-|$)")
    }
}

data class SessionSearchHit(
    val conversationId: String,
    val messageId: Long,
    val title: String,
    val snippet: String,
    val timestampEpochMs: Long,
    val score: Int
)
