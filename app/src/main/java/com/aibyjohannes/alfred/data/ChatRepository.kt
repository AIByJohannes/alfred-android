package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.engine.ChatEngine
import com.aibyjohannes.alfred.core.engine.OpenRouterChatEngine
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val apiKeyStore: ApiKeyStore) {
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        val apiKey = apiKeyStore.loadOpenRouterKey()
            ?: return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))

        val engine = createEngine(apiKey)

        return engine.sendMessage(
            userMessage = userMessage,
            conversationHistory = conversationHistory.toCoreMessages()
        ).map { it.content }
    }

    fun streamMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Flow<ChatStreamEvent> {
        val apiKey = apiKeyStore.loadOpenRouterKey()
            ?: throw IllegalStateException("API key not configured. Please add your OpenRouter API key in Settings.")
        return createEngine(apiKey).streamMessage(
            userMessage = userMessage,
            conversationHistory = conversationHistory.toCoreMessages()
        )
    }

    private fun List<ChatMessage>.toCoreMessages(): List<CoreChatMessage> = map {
        CoreChatMessage(role = it.role, content = it.content)
    }

    private fun createEngine(apiKey: String): ChatEngine {
        return OpenRouterChatEngine(
            apiKey = apiKey,
            model = DEFAULT_MODEL,
            prompt = SystemPrompts.SYSTEM_PROMPT,
            webSearchClient = PerplexitySearchClient(
                apiKey = apiKey,
                model = PERPLEXITY_MODEL
            )
        )
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
