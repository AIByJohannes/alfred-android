package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.ToolCallTrace
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenRouterChatEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val prompt: String = SystemPrompts.SYSTEM_PROMPT,
    private val webSearchClient: WebSearchClient
) : ChatEngine {
    private val objectMapper = ObjectMapper()

    private fun buildClient(): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl("https://openrouter.ai/api/v1")
            .build()
    }

    override suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult> {
        return try {
            val client = buildClient()

            val messages = mutableListOf<ChatCompletionMessageParam>()
            messages.add(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(prompt)
                        .build()
                )
            )

            for (msg in conversationHistory) {
                when (msg.role) {
                    "user" -> messages.add(
                        ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                .content(msg.content)
                                .build()
                        )
                    )
                    "assistant" -> messages.add(
                        ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder()
                                .content(msg.content)
                                .build()
                        )
                    )
                }
            }

            messages.add(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(userMessage)
                        .build()
                )
            )

            val params = ChatCompletionCreateParams.builder()
                .model(model)
                .messages(messages)
                .addFunctionTool(buildWebSearchFunctionDefinition())
                .build()

            val response = withContext(Dispatchers.IO) {
                client.chat().completions().create(params)
            }

            val choice = response.choices().firstOrNull()
                ?: return Result.failure(Exception("No response from AI"))

            val assistantMessage = choice.message()
            val toolCalls = assistantMessage.toolCalls()
            val traces = mutableListOf<ToolCallTrace>()

            if (toolCalls.isPresent && toolCalls.get().isNotEmpty()) {
                val followUpBuilder = ChatCompletionCreateParams.builder()
                    .model(model)
                    .messages(messages)

                followUpBuilder.addMessage(assistantMessage)

                for (toolCall in toolCalls.get()) {
                    val functionToolCall = toolCall.asFunction()
                    val function = functionToolCall.function()
                    val arguments = function.arguments()
                    val result = when (function.name()) {
                        WEB_SEARCH_FUNCTION_NAME -> {
                            val query = extractWebSearchQuery(arguments)
                            if (query.isNullOrBlank()) {
                                "Web search failed: missing required 'query' argument."
                            } else {
                                val searchResult = webSearchClient.search(query)
                                searchResult.getOrElse { "Web search failed: ${it.message}" }
                            }
                        }
                        else -> {
                            "Unknown tool: ${function.name()}"
                        }
                    }

                    val isError = result.startsWith("Web search failed", ignoreCase = true) ||
                        result.startsWith("Unknown tool", ignoreCase = true)
                    traces.add(
                        ToolCallTrace(
                            name = function.name(),
                            argumentsJson = arguments,
                            resultPreview = result.take(400),
                            isError = isError
                        )
                    )

                    followUpBuilder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                            .toolCallId(functionToolCall.id())
                            .contentAsJson(result)
                            .build()
                    )
                }

                val finalResponse = withContext(Dispatchers.IO) {
                    client.chat().completions().create(followUpBuilder.build())
                }

                val finalContent = finalResponse.choices().firstOrNull()?.message()?.content()?.orElse(null)
                    ?: return Result.failure(Exception("No final response from AI"))

                Result.success(ChatTurnResult(content = finalContent, toolCalls = traces))
            } else {
                val content = assistantMessage.content().orElse(null)
                    ?: return Result.failure(Exception("No response content from AI"))
                Result.success(ChatTurnResult(content = content, toolCalls = traces))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildWebSearchFunctionDefinition(): FunctionDefinition {
        val properties = mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "The search query to look up on the web"
            )
        )

        val parameters = FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(listOf("query")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

        return FunctionDefinition.builder()
            .name(WEB_SEARCH_FUNCTION_NAME)
            .description("Search the web for current information.")
            .parameters(parameters)
            .build()
    }

    private fun extractWebSearchQuery(argumentsJson: String): String? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            node.path("query").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.1-flash-lite-preview"
        const val WEB_SEARCH_FUNCTION_NAME = "WebSearchTool"
    }
}
