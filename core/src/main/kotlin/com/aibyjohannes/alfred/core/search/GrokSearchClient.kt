package com.aibyjohannes.alfred.core.search

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GrokSearchClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : WebSearchClient {

    private fun buildClient(): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl("https://openrouter.ai/api/v1")
            .build()
    }

    fun buildSearchParams(query: String): ChatCompletionCreateParams {
        val messages = listOf(
            ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder()
                    .content(
                        "You are a helpful web search assistant. Provide concise, accurate, and current information. Include sources when available."
                    )
                    .build()
            ),
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(query)
                    .build()
            )
        )

        val webSearchTool = listOf(
            mapOf(
                "type" to "openrouter:web_search",
                "parameters" to mapOf("engine" to "native")
            )
        )

        return ChatCompletionCreateParams.builder()
            .model(model)
            .messages(messages)
            .putAdditionalBodyProperty("tools", JsonValue.from(webSearchTool))
            .build()
    }

    override suspend fun search(query: String): Result<String> {
        return try {
            val client = buildClient()
            val params = buildSearchParams(query)

            val response = withContext(Dispatchers.IO) {
                client.chat().completions().create(params)
            }

            val content = response.choices().firstOrNull()?.message()?.content()?.orElse(null)
                ?: return Result.failure(Exception("No response from search model"))

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(Exception("Web search failed: ${e.message}"))
        }
    }

    companion object {
        const val DEFAULT_MODEL = "x-ai/grok-4.3"
    }
}
