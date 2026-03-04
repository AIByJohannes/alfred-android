package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.data.agent.PerplexitySubAgent
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(private val apiKeyStore: ApiKeyStore) {

    private fun buildClient(): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(apiKeyStore.loadOpenRouterKey() ?: "")
            .baseUrl("https://openrouter.ai/api/v1")
            .build()
    }

    private val perplexitySubAgent = PerplexitySubAgent(apiKeyStore)

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        return try {
            if (!apiKeyStore.hasApiKey()) {
                return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
            }

            val client = buildClient()

            // Build the message list
            val messages = mutableListOf<ChatCompletionMessageParam>()
            messages.add(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(Prompts.SYSTEM_PROMPT)
                        .build()
                )
            )

            // Add conversation history
            for (msg in conversationHistory) {
                when (msg.role) {
                    ChatMessage.ROLE_USER -> messages.add(
                        ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                .content(msg.content)
                                .build()
                        )
                    )
                    ChatMessage.ROLE_ASSISTANT -> messages.add(
                        ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder()
                                .content(msg.content)
                                .build()
                        )
                    )
                }
            }

            // Add the new user message
            messages.add(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(userMessage)
                        .build()
                )
            )

            // First request - with tool support
            val params = ChatCompletionCreateParams.builder()
                .model(DEFAULT_MODEL)
                .messages(messages)
                .addTool(WebSearchTool::class.java)
                .build()

            val response = withContext(Dispatchers.IO) {
                client.chat().completions().create(params)
            }

            val choice = response.choices().firstOrNull()
                ?: return Result.failure(Exception("No response from AI"))

            val assistantMessage = choice.message()
            val toolCalls = assistantMessage.toolCalls()

            if (toolCalls.isPresent && toolCalls.get().isNotEmpty()) {
                // Process tool calls
                val followUpBuilder = ChatCompletionCreateParams.builder()
                    .model(DEFAULT_MODEL)
                    .messages(messages)

                // Add assistant message (including its tool calls) to conversation
                followUpBuilder.addMessage(assistantMessage)

                for (toolCall in toolCalls.get()) {
                    val functionToolCall = toolCall.asFunction()
                    val function = functionToolCall.function()
                    val result = when (function.name()) {
                        "WebSearchTool" -> {
                            val args = function.arguments(WebSearchTool::class.java)
                            val searchResult = perplexitySubAgent.webSearch(args.query ?: "")
                            searchResult.getOrElse { "Web search failed: ${it.message}" }
                        }
                        else -> "Unknown tool: ${function.name()}"
                    }

                    followUpBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                            .toolCallId(functionToolCall.id())
                            .contentAsJson(result)
                            .build()
                    )
                }

                // Follow-up request with tool results
                val followUpParams = followUpBuilder.build()

                val finalResponse = withContext(Dispatchers.IO) {
                    client.chat().completions().create(followUpParams)
                }

                val finalContent = finalResponse.choices().firstOrNull()?.message()?.content()?.orElse(null)
                    ?: return Result.failure(Exception("No final response from AI"))

                Result.success(finalContent)
            } else {
                // No tool calls, return direct response
                val content = assistantMessage.content().orElse(null)
                    ?: return Result.failure(Exception("No response content from AI"))
                Result.success(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    @JsonClassDescription("Search the web for current information. Use this when you need up-to-date information about recent events, news, current facts, or anything that requires internet access.")
    class WebSearchTool {
        @JsonPropertyDescription("The search query to look up on the web")
        var query: String? = null
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite"
        const val PERPLEXITY_MODEL = "perplexity/sonar"
    }
}
