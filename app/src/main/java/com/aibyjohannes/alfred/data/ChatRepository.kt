package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.engine.OpenRouterChatEngine
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

class ChatRepository(private val apiKeyStore: ApiKeyStore) {
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        val apiKey = apiKeyStore.loadOpenRouterKey()
            ?: return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))

        val engine = OpenRouterChatEngine(
            apiKey = apiKey,
            model = DEFAULT_MODEL,
            prompt = SystemPrompts.SYSTEM_PROMPT,
            webSearchClient = PerplexitySearchClient(
                apiKey = apiKey,
                model = PERPLEXITY_MODEL
            )
        )

        return engine.sendMessage(
            userMessage = userMessage,
            conversationHistory = conversationHistory.map {
                CoreChatMessage(role = it.role, content = it.content)
            }
        ).map { it.content }
    }

    // Retained for test compatibility and tool schema intent.
    @JsonClassDescription("Search the web for current information.")
    class WebSearchTool {
        @JsonPropertyDescription("The search query to look up on the web")
        var query: String? = null
    }

    companion object {
        const val DEFAULT_MODEL = OpenRouterChatEngine.DEFAULT_MODEL
        const val PERPLEXITY_MODEL = PerplexitySearchClient.DEFAULT_MODEL
    }

    @Deprecated("Use core engine package directly for new integrations.")
    class PerplexitySubAgent(private val apiKeyStore: ApiKeyStore) {
        suspend fun webSearch(query: String): Result<String> {
            val apiKey = apiKeyStore.loadOpenRouterKey()
                ?: return Result.failure(Exception("API key not configured."))
            return PerplexitySearchClient(apiKey = apiKey, model = PERPLEXITY_MODEL).search(query)
        }
    }
}
