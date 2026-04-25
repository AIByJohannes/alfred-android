package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileConversationStore(
    private val rootDir: File
) : ConversationStore {
    private val lock = Any()
    private val objectMapper = ObjectMapper()
    private val conversationsDir = File(rootDir, "conversations")
    private val metadataFile = File(rootDir, "conversations.json")

    override suspend fun getOrCreateActiveConversation(): ConversationSummary = withStoreLock {
        val state = readState()
        val active = state.activeConversationId
            ?.let { id -> state.conversations.firstOrNull { it.id == id } }
        if (active != null) {
            active.toSummary()
        } else {
            createConversationInternal(state)
        }
    }

    override suspend fun listConversations(): List<ConversationSummary> = withStoreLock {
        readState().conversations
            .sortedByDescending { it.updatedAtEpochMs }
            .map { it.toSummary() }
    }

    override suspend fun createConversation(): ConversationSummary = withStoreLock {
        createConversationInternal(readState())
    }

    override suspend fun switchActiveConversation(conversationId: Long): ConversationSummary = withStoreLock {
        val state = readState()
        val conversation = state.conversations.firstOrNull { it.id == conversationId }
            ?: throw IllegalArgumentException("Conversation not found: $conversationId")
        state.activeConversationId = conversationId
        writeState(state)
        conversation.toSummary()
    }

    override suspend fun loadMessages(conversationId: Long): List<StoredChatMessage> = withStoreLock {
        readMessages(conversationId).map {
            StoredChatMessage(
                id = it.id,
                role = it.role,
                content = it.content
            )
        }
    }

    override suspend fun appendMessage(conversationId: Long, role: String, content: String): Unit = withStoreLock {
        val state = readState()
        val conversation = state.conversations.firstOrNull { it.id == conversationId }
            ?: throw IllegalArgumentException("Conversation not found: $conversationId")
        val now = System.currentTimeMillis()
        val message = MessageRecord().apply {
            id = state.nextMessageId++
            this.conversationId = conversationId
            this.role = role
            this.content = content
            createdAtEpochMs = now
        }

        appendMessageRecord(message)
        if (role == ChatMessage.ROLE_USER && conversation.title.isNullOrBlank()) {
            conversation.title = buildConversationTitle(content)
        }
        conversation.updatedAtEpochMs = now
        writeState(state)
    }

    override suspend fun deleteConversation(conversationId: Long): Unit = withStoreLock {
        val state = readState()
        state.conversations.removeAll { it.id == conversationId }
        if (state.activeConversationId == conversationId) {
            state.activeConversationId = null
        }
        messageFile(conversationId).delete()
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
            readMessages(conversation.id).mapNotNull { message ->
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
            ensureDirectories()
            block()
        }
    }

    private fun ensureDirectories() {
        rootDir.mkdirs()
        conversationsDir.mkdirs()
    }

    private fun createConversationInternal(state: StoreState): ConversationSummary {
        val now = System.currentTimeMillis()
        val id = state.nextConversationId++
        val conversation = ConversationRecord().apply {
            this.id = id
            title = null
            createdAtEpochMs = now
            updatedAtEpochMs = now
        }
        state.activeConversationId = id
        state.conversations.add(conversation)
        writeState(state)
        messageFile(id).createNewFile()
        return conversation.toSummary()
    }

    private fun readState(): StoreState {
        if (!metadataFile.exists() || metadataFile.length() == 0L) {
            return StoreState()
        }
        return objectMapper.readValue(metadataFile, StoreState::class.java)
    }

    private fun writeState(state: StoreState) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataFile, state)
    }

    private fun readMessages(conversationId: Long): List<MessageRecord> {
        val file = messageFile(conversationId)
        if (!file.exists()) {
            return emptyList()
        }
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { objectMapper.readValue(it, MessageRecord::class.java) }
    }

    private fun appendMessageRecord(message: MessageRecord) {
        messageFile(message.conversationId).appendText(objectMapper.writeValueAsString(message) + "\n")
    }

    private fun messageFile(conversationId: Long): File = File(conversationsDir, "$conversationId.jsonl")

    private fun ConversationRecord.toSummary(): ConversationSummary {
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

    private class StoreState {
        var activeConversationId: Long? = null
        var nextConversationId: Long = 1L
        var nextMessageId: Long = 1L
        var conversations: MutableList<ConversationRecord> = mutableListOf()
    }

    private class ConversationRecord {
        var id: Long = 0L
        var title: String? = null
        var createdAtEpochMs: Long = 0L
        var updatedAtEpochMs: Long = 0L
    }

    private class MessageRecord {
        var id: Long = 0L
        var conversationId: Long = 0L
        var role: String = ""
        var content: String = ""
        var createdAtEpochMs: Long = 0L
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
