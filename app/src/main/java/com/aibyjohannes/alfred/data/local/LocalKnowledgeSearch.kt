package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class WorkspaceMemory(
    val id: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val content: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs
)

class FileMemorySearchSource private constructor(
    private val rootDirectory: File,
    private val legacyMemoryFile: File?,
    private val fixedMemoryFile: File?
) {
    constructor(memoryFile: File) : this(
        rootDirectory = memoryFile.parentFile?.resolve("workspace_memories") ?: File("workspace_memories"),
        legacyMemoryFile = null,
        fixedMemoryFile = memoryFile
    )

    constructor(rootDirectory: File, legacyMemoryFile: File) : this(
        rootDirectory = rootDirectory,
        legacyMemoryFile = legacyMemoryFile,
        fixedMemoryFile = null
    )

    private val objectMapper = ObjectMapper()
    private val migrationMutex = Mutex()

    /** Compatibility entry point for fixed-file sources used by focused tests and imports. */
    suspend fun search(query: String, limit: Int): List<MemorySearchHit> =
        searchFile(requireNotNull(fixedMemoryFile ?: legacyMemoryFile), query, limit)

    suspend fun search(workspaceId: String, query: String, limit: Int): List<MemorySearchHit> {
        fixedMemoryFile?.let { return searchFile(it, query, limit) }
        val memoryFile = prepareWorkspace(workspaceId)
        return searchFile(memoryFile, query, limit)
    }

    suspend fun save(workspaceId: String, memory: WorkspaceMemory) = withContext(Dispatchers.IO) {
        val memoryFile = prepareWorkspace(workspaceId)
        val node = objectMapper.createObjectNode().apply {
            put("id", memory.id)
            put("workspaceId", workspaceId)
            memory.title?.let { put("title", it) }
            put("content", memory.content)
            put("createdAtEpochMs", memory.createdAtEpochMs)
            put("updatedAtEpochMs", memory.updatedAtEpochMs)
        }
        memoryFile.appendText(objectMapper.writeValueAsString(node) + "\n")
    }

    suspend fun deleteWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        val directory = workspaceDirectory(workspaceId)
        if (directory.exists()) {
            directory.listFiles().orEmpty().forEach { child ->
                if (child.isFile) child.delete()
            }
            directory.delete()
        }
    }

    internal fun workspaceMemoryFile(workspaceId: String): File =
        workspaceDirectory(workspaceId).resolve(MEMORY_FILE_NAME)

    private suspend fun prepareWorkspace(workspaceId: String): File = migrationMutex.withLock {
        val target = workspaceMemoryFile(workspaceId)
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val marker = rootDirectory.resolve(LEGACY_MIGRATION_MARKER)
            val legacy = legacyMemoryFile
            if (legacy != null && legacy.isFile && !marker.exists()) {
                migrateLegacyFile(legacy, target, workspaceId)
                marker.parentFile?.mkdirs()
                marker.writeText(workspaceId)
            }
            if (!target.exists()) target.createNewFile()
            target
        }
    }

    private fun migrateLegacyFile(legacy: File, target: File, workspaceId: String) {
        val existingIds = if (target.isFile) {
            target.readLines().mapNotNull(::recordId).toMutableSet()
        } else {
            mutableSetOf()
        }
        val migrated = legacy.readLines().filter(String::isNotBlank).mapNotNull { line ->
            runCatching {
                val node = objectMapper.readTree(line) as? ObjectNode ?: return@runCatching null
                val id = node.path("id").asText().takeIf(String::isNotBlank)
                    ?: UUID.nameUUIDFromBytes(line.toByteArray()).toString()
                if (!existingIds.add(id)) return@runCatching null
                node.put("id", id)
                node.put("workspaceId", workspaceId)
                objectMapper.writeValueAsString(node)
            }.getOrNull()
        }
        if (migrated.isNotEmpty()) {
            target.parentFile?.mkdirs()
            target.appendText(migrated.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun recordId(line: String): String? = runCatching {
        objectMapper.readTree(line).path("id").asText().takeIf(String::isNotBlank)
    }.getOrNull()

    private fun workspaceDirectory(workspaceId: String): File {
        require(workspaceId.isNotBlank() && workspaceId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Invalid workspace ID"
        }
        return rootDirectory.resolve("workspace-$workspaceId")
    }

    private suspend fun searchFile(memoryFile: File, query: String, limit: Int): List<MemorySearchHit> =
        withContext(Dispatchers.IO) {
            val terms = normalizeTerms(query)
            if (terms.isEmpty() || !memoryFile.exists()) return@withContext emptyList()

            memoryFile.readLines()
                .filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching { objectMapper.readValue(line, MemoryRecord::class.java) }.getOrNull()?.let { record ->
                        val haystack = listOfNotNull(record.title, record.content).joinToString(" ")
                        val score = score(haystack, terms)
                        if (score <= 0) null else MemorySearchHit(
                            memoryId = record.id,
                            title = record.title?.takeIf { it.isNotBlank() } ?: "Memory",
                            snippet = buildSnippet(record.content, terms),
                            timestampEpochMs = record.updatedAtEpochMs.takeIf { it > 0L } ?: record.createdAtEpochMs,
                            score = score
                        )
                    }
                }
                .sortedWith(compareByDescending<MemorySearchHit> { it.score }.thenByDescending { it.timestampEpochMs })
                .take(limit)
        }

    private fun normalizeTerms(query: String): List<String> = query.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }
        .distinct()

    private fun score(content: String, terms: List<String>): Int {
        val normalized = content.lowercase()
        return terms.sumOf { term -> Regex("\\b${Regex.escape(term)}\\b").findAll(normalized).count() }
    }

    private fun buildSnippet(content: String, terms: List<String>): String {
        val lower = content.lowercase()
        val firstMatch = terms.mapNotNull { lower.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull() ?: 0
        val start = (firstMatch - 80).coerceAtLeast(0)
        val end = (firstMatch + 220).coerceAtMost(content.length)
        return (if (start > 0) "..." else "") + content.substring(start, end).trim() +
            (if (end < content.length) "..." else "")
    }

    private class MemoryRecord {
        var id: String = ""
        var workspaceId: String = ""
        var title: String? = null
        var content: String = ""
        var createdAtEpochMs: Long = 0L
        var updatedAtEpochMs: Long = 0L
    }

    private companion object {
        const val MEMORY_FILE_NAME = "memories.jsonl"
        const val LEGACY_MIGRATION_MARKER = ".legacy-memory-migrated"
    }
}

class FileLocalKnowledgeSearchClient(
    private val conversationStore: FileConversationStore,
    private val memorySearchSource: FileMemorySearchSource
) : LocalKnowledgeSearchClient {
    override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> = try {
        val limit = request.limit.coerceIn(1, 10)
        val workspaceId = conversationStore.getOrCreateActiveWorkspace().id
        val sessionResults = if (request.source == LocalKnowledgeSource.ALL || request.source == LocalKnowledgeSource.SESSIONS) {
            conversationStore.searchSessionMessages(workspaceId, request.query, limit).map {
                LocalKnowledgeSearchResult(
                    source = LocalKnowledgeSource.SESSIONS,
                    title = it.title,
                    snippet = it.snippet,
                    timestampEpochMs = it.timestampEpochMs,
                    conversationId = it.conversationId,
                    messageId = it.messageId
                )
            }
        } else emptyList()

        val memoryResults = if (request.source == LocalKnowledgeSource.ALL || request.source == LocalKnowledgeSource.MEMORIES) {
            memorySearchSource.search(workspaceId, request.query, limit).map {
                LocalKnowledgeSearchResult(
                    source = LocalKnowledgeSource.MEMORIES,
                    title = it.title,
                    snippet = it.snippet,
                    timestampEpochMs = it.timestampEpochMs,
                    memoryId = it.memoryId
                )
            }
        } else emptyList()

        Result.success((sessionResults + memoryResults).sortedByDescending { it.timestampEpochMs }.take(limit))
    } catch (error: Exception) {
        Result.failure(error)
    }
}

data class MemorySearchHit(
    val memoryId: String,
    val title: String,
    val snippet: String,
    val timestampEpochMs: Long,
    val score: Int
)
