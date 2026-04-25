package com.aibyjohannes.alfred.core.search

enum class LocalKnowledgeSource {
    ALL,
    SESSIONS,
    MEMORIES
}

data class LocalKnowledgeSearchRequest(
    val query: String,
    val limit: Int = 5,
    val source: LocalKnowledgeSource = LocalKnowledgeSource.ALL
)

data class LocalKnowledgeSearchResult(
    val source: LocalKnowledgeSource,
    val title: String,
    val snippet: String,
    val timestampEpochMs: Long,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val memoryId: String? = null
)

interface LocalKnowledgeSearchClient {
    suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>>
}
