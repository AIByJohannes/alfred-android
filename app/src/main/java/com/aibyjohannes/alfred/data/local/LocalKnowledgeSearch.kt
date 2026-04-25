package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileMemorySearchSource(
    private val memoryFile: File
) {
    private val objectMapper = ObjectMapper()

    suspend fun search(query: String, limit: Int): List<MemorySearchHit> = withContext(Dispatchers.IO) {
        val terms = normalizeTerms(query)
        if (terms.isEmpty() || !memoryFile.exists()) {
            return@withContext emptyList()
        }

        memoryFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val record = objectMapper.readValue(line, MemoryRecord::class.java)
                val haystack = listOfNotNull(record.title, record.content).joinToString(" ")
                val score = score(haystack, terms)
                if (score <= 0) {
                    null
                } else {
                    MemorySearchHit(
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

    private class MemoryRecord {
        var id: String = ""
        var title: String? = null
        var content: String = ""
        var createdAtEpochMs: Long = 0L
        var updatedAtEpochMs: Long = 0L
    }
}

class FileLocalKnowledgeSearchClient(
    private val conversationStore: FileConversationStore,
    private val memorySearchSource: FileMemorySearchSource
) : LocalKnowledgeSearchClient {
    override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> {
        return try {
            val limit = request.limit.coerceIn(1, 10)
            val sessionResults = if (request.source == LocalKnowledgeSource.ALL || request.source == LocalKnowledgeSource.SESSIONS) {
                conversationStore.searchSessionMessages(request.query, limit).map {
                    LocalKnowledgeSearchResult(
                        source = LocalKnowledgeSource.SESSIONS,
                        title = it.title,
                        snippet = it.snippet,
                        timestampEpochMs = it.timestampEpochMs,
                        conversationId = it.conversationId,
                        messageId = it.messageId
                    )
                }
            } else {
                emptyList()
            }

            val memoryResults = if (request.source == LocalKnowledgeSource.ALL || request.source == LocalKnowledgeSource.MEMORIES) {
                memorySearchSource.search(request.query, limit).map {
                    LocalKnowledgeSearchResult(
                        source = LocalKnowledgeSource.MEMORIES,
                        title = it.title,
                        snippet = it.snippet,
                        timestampEpochMs = it.timestampEpochMs,
                        memoryId = it.memoryId
                    )
                }
            } else {
                emptyList()
            }

            Result.success(
                (sessionResults + memoryResults)
                    .sortedByDescending { it.timestampEpochMs }
                    .take(limit)
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class MemorySearchHit(
    val memoryId: String,
    val title: String,
    val snippet: String,
    val timestampEpochMs: Long,
    val score: Int
)
