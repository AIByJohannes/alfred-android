package com.aibyjohannes.alfred.data.agent

import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient

/**
 * Perplexity Sub-Agent for web search capabilities.
 *
 * This agent uses Perplexity Sonar via OpenRouter to perform web searches
 * and return current information from the internet.
 */
class PerplexitySubAgent(private val apiKeyStore: ApiKeyStore) {
    /**
     * Perform a web search using Perplexity Sonar
     *
     * @param query The search query to look up
     * @return The search results as a formatted string, or an error message
     */
    suspend fun webSearch(query: String): Result<String> {
        val apiKey = apiKeyStore.loadOpenRouterKey()
            ?: return Result.failure(Exception("API key not configured."))
        return PerplexitySearchClient(
            apiKey = apiKey,
            model = ChatRepository.PERPLEXITY_MODEL
        ).search(query)
    }
}
