package com.aibyjohannes.alfred.core.search

interface WebSearchClient {
    suspend fun search(query: String): Result<String>
}
