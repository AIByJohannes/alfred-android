package com.aibyjohannes.alfred.data.agent

import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Perplexity Sub-Agent for web search capabilities.
 *
 * This agent uses Perplexity Sonar via OpenRouter to perform web searches
 * and return current information from the internet.
 */
class PerplexitySubAgent(private val apiKeyStore: ApiKeyStore) {

    private fun buildClient(): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(apiKeyStore.loadOpenRouterKey() ?: "")
            .baseUrl("https://openrouter.ai/api/v1")
            .build()
    }

    /**
     * Perform a web search using Perplexity Sonar
     *
     * @param query The search query to look up
     * @return The search results as a formatted string, or an error message
     */
    suspend fun webSearch(query: String): Result<String> {
        return try {
            val client = buildClient()

            val messages = listOf(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content("You are a helpful web search assistant. Provide concise, accurate, and up-to-date information based on your web search capabilities. Include relevant sources when available.")
                        .build()
                ),
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(query)
                        .build()
                )
            )

            val params = ChatCompletionCreateParams.builder()
                .model(ChatRepository.PERPLEXITY_MODEL)
                .messages(messages)
                .build()

            val response = withContext(Dispatchers.IO) {
                client.chat().completions().create(params)
            }

            val content = response.choices().firstOrNull()?.message()?.content()?.orElse(null)
                ?: return Result.failure(Exception("No response from Perplexity"))

            Result.success(content)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(Exception("Web search failed: ${e.message}"))
        }
    }
}
