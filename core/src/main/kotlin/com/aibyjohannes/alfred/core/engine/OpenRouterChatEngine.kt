package com.aibyjohannes.alfred.core.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.ToolCallTrace
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class OpenRouterChatEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val prompt: String = SystemPrompts.SYSTEM_PROMPT,
    private val webSearchClient: WebSearchClient,
    private val localKnowledgeSearchClient: LocalKnowledgeSearchClient? = null
) : ChatEngine {
    private val objectMapper = ObjectMapper()
    private val effectiveLocalKnowledgeSearchClient: LocalKnowledgeSearchClient =
        localKnowledgeSearchClient ?: EmptyLocalKnowledgeSearchClient

    private data class ToolCall(
        val id: String?,
        val name: String,
        val argumentsJson: String
    )

    private data class StreamPassResult(
        val content: String,
        val toolCalls: List<ToolCall>
    )

    override suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Result<ChatTurnResult> {
        return try {
            var completed: ChatTurnResult? = null
            streamMessage(userMessage, conversationHistory).collect { event ->
                if (event is ChatStreamEvent.Completed) {
                    completed = event.result
                }
            }
            completed?.let { Result.success(it) }
                ?: Result.failure(Exception("No final response from AI"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamMessage(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>
    ): Flow<ChatStreamEvent> = channelFlow {
        val finalResult = withContext(Dispatchers.IO) {
            OpenRouterLLMClient(apiKey).use { client ->
                val koogModel = buildKoogModel(model)
                val tools = buildAlfredToolDescriptors()
                val initialPrompt = buildConversationPrompt(userMessage, conversationHistory)
                val traces = mutableListOf<ToolCallTrace>()

                val initialPass = streamSingleCompletion(
                    client = client,
                    model = koogModel,
                    prompt = initialPrompt,
                    tools = tools
                ) { delta ->
                    if (delta.isNotEmpty()) {
                        trySend(ChatStreamEvent.Delta(delta))
                    }
                }

                if (initialPass.toolCalls.isEmpty()) {
                    if (initialPass.content.isBlank()) {
                        throw Exception("No response content from AI")
                    }
                    ChatTurnResult(content = initialPass.content, toolCalls = traces)
                } else {
                    val toolResults = initialPass.toolCalls.map { toolCall ->
                        val result = executeToolCall(toolCall.name, toolCall.argumentsJson)
                        val isError = result.startsWith("Web search failed", ignoreCase = true) ||
                            result.startsWith("Local knowledge search failed", ignoreCase = true) ||
                            result.startsWith("Unknown tool", ignoreCase = true)
                        traces.add(
                            ToolCallTrace(
                                name = toolCall.name,
                                argumentsJson = toolCall.argumentsJson,
                                resultPreview = result.take(400),
                                isError = isError
                            )
                        )
                        ToolResultForPrompt(
                            toolCall = toolCall,
                            result = result
                        )
                    }

                    val followUpPrompt = buildConversationPrompt(
                        userMessage = userMessage,
                        conversationHistory = conversationHistory,
                        assistantToolRequest = initialPass.content,
                        toolResults = toolResults
                    )

                    val finalPass = streamSingleCompletion(
                        client = client,
                        model = koogModel,
                        prompt = followUpPrompt,
                        tools = tools
                    ) { delta ->
                        if (delta.isNotEmpty()) {
                            trySend(ChatStreamEvent.Delta(delta))
                        }
                    }

                    if (finalPass.content.isBlank()) {
                        throw Exception("No final response content from AI")
                    }
                    ChatTurnResult(content = finalPass.content, toolCalls = traces)
                }
            }
        }

        trySend(ChatStreamEvent.Completed(finalResult))
    }

    internal fun buildKoogModel(modelId: String): LLModel {
        return LLModel(
            provider = LLMProvider.OpenRouter,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Temperature
            )
        )
    }

    internal fun buildAlfredToolDescriptors(): List<ToolDescriptor> {
        return listOf(
            ToolDescriptor(
                name = WEB_SEARCH_FUNCTION_NAME,
                description = "Search the web for current information.",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        name = "query",
                        description = "The search query to look up on the web",
                        type = ToolParameterType.String
                    )
                )
            ),
            ToolDescriptor(
                name = LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME,
                description = "Search previous local sessions and memories for user-specific recall.",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        name = "query",
                        description = "Search query for prior local sessions and memories",
                        type = ToolParameterType.String
                    )
                ),
                optionalParameters = listOf(
                    ToolParameterDescriptor(
                        name = "limit",
                        description = "Maximum number of local results to return. Defaults to 5 and is capped at 10.",
                        type = ToolParameterType.Integer
                    ),
                    ToolParameterDescriptor(
                        name = "source",
                        description = "Which local source to search: all, sessions, or memories",
                        type = ToolParameterType.Enum(arrayOf("all", "sessions", "memories"))
                    )
                )
            )
        )
    }

    private data class ToolResultForPrompt(
        val toolCall: ToolCall,
        val result: String
    )

    private fun buildConversationPrompt(
        userMessage: String,
        conversationHistory: List<CoreChatMessage>,
        assistantToolRequest: String? = null,
        toolResults: List<ToolResultForPrompt> = emptyList()
    ) = prompt("alfred-openrouter-chat") {
        system(prompt)

        for (msg in conversationHistory) {
            when (msg.role) {
                "user" -> user(msg.content)
                "assistant" -> assistant(msg.content)
            }
        }

        user(userMessage)

        if (toolResults.isNotEmpty()) {
            if (!assistantToolRequest.isNullOrBlank()) {
                assistant(assistantToolRequest)
            }
            user(formatToolResultsForFollowUp(toolResults))
        }
    }

    private fun formatToolResultsForFollowUp(toolResults: List<ToolResultForPrompt>): String {
        return buildString {
            appendLine("Tool results are available for the previous assistant tool request.")
            appendLine("Use these results to answer the user's original message. Do not call tools again unless required.")
            toolResults.forEachIndexed { index, toolResult ->
                appendLine()
                append(index + 1)
                append(". ")
                append(toolResult.toolCall.name)
                toolResult.toolCall.id?.let { append(" (id=$it)") }
                appendLine()
                append("Arguments JSON: ")
                appendLine(toolResult.toolCall.argumentsJson)
                appendLine("Result:")
                appendLine(toolResult.result)
            }
        }.trim()
    }

    private suspend fun streamSingleCompletion(
        client: OpenRouterLLMClient,
        model: LLModel,
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        onDelta: (String) -> Unit
    ): StreamPassResult {
        val content = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var completedText: String? = null

        client.executeStreaming(prompt, model, tools).collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> {
                    content.append(frame.text)
                    onDelta(frame.text)
                }

                is StreamFrame.TextComplete -> {
                    completedText = frame.text
                }

                is StreamFrame.ToolCallComplete -> {
                    toolCalls.add(
                        ToolCall(
                            id = frame.id,
                            name = frame.name,
                            argumentsJson = frame.content
                        )
                    )
                }

                else -> Unit
            }
        }

        return StreamPassResult(
            content = completedText ?: content.toString(),
            toolCalls = toolCalls
        )
    }

    internal suspend fun executeToolCall(functionName: String, arguments: String): String {
        return when (functionName) {
            WEB_SEARCH_FUNCTION_NAME -> {
                val query = extractWebSearchQuery(arguments)
                if (query.isNullOrBlank()) {
                    "Web search failed: missing required 'query' argument."
                } else {
                    val searchResult = webSearchClient.search(query)
                    searchResult.getOrElse { "Web search failed: ${it.message}" }
                }
            }

            LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME -> {
                val request = extractLocalKnowledgeSearchRequest(arguments)
                if (request == null) {
                    "Local knowledge search failed: missing required 'query' argument."
                } else {
                    val searchResult = effectiveLocalKnowledgeSearchClient.search(request)
                    searchResult.fold(
                        onSuccess = { formatLocalKnowledgeResults(it) },
                        onFailure = { "Local knowledge search failed: ${it.message}" }
                    )
                }
            }

            else -> {
                "Unknown tool: $functionName"
            }
        }
    }

    internal fun extractWebSearchQuery(argumentsJson: String): String? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            node.path("query").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractLocalKnowledgeSearchRequest(argumentsJson: String): LocalKnowledgeSearchRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val query = node.path("query").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val limit = if (node.has("limit")) node.path("limit").asInt(5) else 5
            val source = when (node.path("source").asText("all").lowercase()) {
                "sessions" -> LocalKnowledgeSource.SESSIONS
                "memories" -> LocalKnowledgeSource.MEMORIES
                else -> LocalKnowledgeSource.ALL
            }
            LocalKnowledgeSearchRequest(
                query = query,
                limit = limit.coerceIn(1, 10),
                source = source
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun formatLocalKnowledgeResults(results: List<LocalKnowledgeSearchResult>): String {
        if (results.isEmpty()) {
            return "No local sessions or memories matched the query."
        }

        return buildString {
            appendLine("Local knowledge search results:")
            results.forEachIndexed { index, result ->
                append(index + 1)
                append(". [")
                append(result.source.name.lowercase())
                append("] ")
                append(result.title)
                append(" (timestampEpochMs=")
                append(result.timestampEpochMs)
                result.conversationId?.let { append(", conversationId=$it") }
                result.messageId?.let { append(", messageId=$it") }
                result.memoryId?.let { append(", memoryId=$it") }
                appendLine(")")
                appendLine(result.snippet)
            }
        }.trim()
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.5-flash"
        const val WEB_SEARCH_FUNCTION_NAME = "WebSearchTool"
        const val LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME = "SearchLocalKnowledgeTool"
    }
}

private object EmptyLocalKnowledgeSearchClient : LocalKnowledgeSearchClient {
    override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> {
        return Result.success(emptyList())
    }
}
