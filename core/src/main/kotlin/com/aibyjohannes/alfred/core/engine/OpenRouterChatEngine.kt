package com.aibyjohannes.alfred.core.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.model.ChatTurnResult
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.core.model.ToolCallTrace
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.aibyjohannes.alfred.core.ticktick.TickTickClient
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OpenRouterChatEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val prompt: String = SystemPrompts.SYSTEM_PROMPT,
    private val webSearchClient: WebSearchClient,
    private val localKnowledgeSearchClient: LocalKnowledgeSearchClient? = null,
    private val tickTickClient: TickTickClient? = null
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
        val toolCalls: List<ToolCall>,
        val reasoning: List<ReasoningPart>,
        val emittedAnyFrame: Boolean
    )

    private data class ReasoningPart(
        val id: String?,
        val content: List<String>,
        val summary: List<String>,
        val encrypted: String?
    )

    private data class PassWithPromptState(
        val result: StreamPassResult,
        val reasoningEnabled: Boolean
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
            createOpenRouterClient().use { client ->
                val koogModel = buildKoogModel(model)
                val mcpTools = tickTickClient?.getMcpTools() ?: emptyList()
                val tools = buildAlfredToolDescriptors(mcpTools)
                val traces = mutableListOf<ToolCallTrace>()
                val turnId = System.currentTimeMillis().toString()
                val promptMessages = conversationHistory
                    .filter { it.includeInPrompt }
                    .toMutableList()
                promptMessages.add(
                    CoreChatMessage(
                        role = "user",
                        content = userMessage,
                        turnId = turnId
                    )
                )
                val persistedIntermediateMessages = mutableListOf<CoreChatMessage>()
                var reasoningEnabled = true
                var finalContent: String? = null
                var passIndex = 0

                while (passIndex < MAX_AGENT_PASSES && finalContent == null) {
                    send(ChatStreamEvent.PassStarted(passIndex))
                    val pass = streamSingleCompletionWithReasoningFallback(
                        client = client,
                        model = koogModel,
                        messages = promptMessages,
                        tools = tools,
                        passIndex = passIndex,
                        reasoningEnabled = reasoningEnabled,
                        onEvent = { this@channelFlow.send(it) }
                    )
                    reasoningEnabled = pass.reasoningEnabled
                    val passResult = pass.result

                    passResult.reasoning.forEach { reasoning ->
                        val text = reasoning.content.joinToString("\n").trim()
                        val summary = reasoning.summary.joinToString("\n").trim()
                        val message = CoreChatMessage(
                            role = "assistant",
                            content = summary.ifBlank { text },
                            kind = CoreChatMessageKind.REASONING,
                            turnId = turnId,
                            reasoningText = text.ifBlank { null },
                            reasoningSummary = summary.ifBlank { null },
                            encryptedReasoning = reasoning.encrypted,
                            includeInPrompt = true,
                            searchable = false
                        )
                        promptMessages.add(message)
                        persistedIntermediateMessages.add(message)
                    }

                    if (passResult.toolCalls.isEmpty()) {
                        if (passResult.content.isBlank()) {
                            throw Exception("No response content from AI")
                        }
                        finalContent = passResult.content
                        send(ChatStreamEvent.PassCompleted(passIndex))
                        break
                    }

                    if (passResult.content.isNotBlank()) {
                        val draftMessage = CoreChatMessage(
                            role = "assistant",
                            content = passResult.content,
                            kind = CoreChatMessageKind.MESSAGE,
                            turnId = turnId,
                            includeInPrompt = true,
                            searchable = false
                        )
                        promptMessages.add(draftMessage)
                        persistedIntermediateMessages.add(draftMessage)
                    }

                    passResult.toolCalls.forEach { toolCall ->
                        val toolCallMessage = CoreChatMessage(
                            role = "assistant",
                            content = toolCall.argumentsJson,
                            kind = CoreChatMessageKind.TOOL_CALL,
                            turnId = turnId,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            toolArgumentsJson = toolCall.argumentsJson,
                            includeInPrompt = true,
                            searchable = false
                        )
                        promptMessages.add(toolCallMessage)
                        persistedIntermediateMessages.add(toolCallMessage)
                        send(
                            ChatStreamEvent.ToolCallRequested(
                                passIndex = passIndex,
                                toolCallId = toolCall.id,
                                name = toolCall.name,
                                argumentsJson = toolCall.argumentsJson
                            )
                        )

                        val result = executeToolCall(toolCall.name, toolCall.argumentsJson)
                        val isError = result.startsWith("Web search failed", ignoreCase = true) ||
                            result.startsWith("Local knowledge search failed", ignoreCase = true) ||
                            result.startsWith("Unknown tool", ignoreCase = true) ||
                            result.startsWith("TickTick failed", ignoreCase = true) ||
                            result.startsWith("TickTick integration is not configured", ignoreCase = true) ||
                            result.startsWith("Error:", ignoreCase = true) ||
                            result.startsWith("Unknown TickTick action", ignoreCase = true) ||
                            result.startsWith("Smart model delegation failed", ignoreCase = true)
                        traces.add(
                            ToolCallTrace(
                                id = toolCall.id,
                                name = toolCall.name,
                                argumentsJson = toolCall.argumentsJson,
                                resultPreview = result.take(400),
                                isError = isError
                            )
                        )
                        val resultMessage = CoreChatMessage(
                            role = "tool",
                            content = result,
                            kind = CoreChatMessageKind.TOOL_RESULT,
                            turnId = turnId,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            isError = isError,
                            includeInPrompt = true,
                            searchable = false
                        )
                        promptMessages.add(resultMessage)
                        persistedIntermediateMessages.add(resultMessage)
                        send(
                            ChatStreamEvent.ToolResultAvailable(
                                passIndex = passIndex,
                                toolCallId = toolCall.id,
                                name = toolCall.name,
                                resultPreview = result.take(400),
                                isError = isError
                            )
                        )
                    }
                    send(ChatStreamEvent.PassCompleted(passIndex))
                    passIndex++
                }

                val content = finalContent ?: throw Exception("Tool loop exceeded $MAX_AGENT_PASSES passes")
                val finalMessage = CoreChatMessage(
                    role = "assistant",
                    content = content,
                    kind = CoreChatMessageKind.MESSAGE,
                    turnId = turnId,
                    includeInPrompt = true,
                    searchable = true
                )
                ChatTurnResult(
                    content = content,
                    toolCalls = traces,
                    intermediateMessages = persistedIntermediateMessages + finalMessage
                )
            }
        }

        send(ChatStreamEvent.Completed(finalResult))
    }

    fun createOpenRouterClient(): OpenRouterLLMClient {
        return OpenRouterLLMClient(apiKey, OpenRouterClientSettings(), KtorKoogHttpClient.Factory())
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

    internal fun buildAlfredToolDescriptors(mcpTools: List<ToolDescriptor> = emptyList()): List<ToolDescriptor> {
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
            ),
            ToolDescriptor(
                name = ASK_SMART_MODEL_FUNCTION_NAME,
                description = "Delegate complex planning, logical reasoning, or step-by-step structuring tasks to a stronger reasoning model (DeepSeek Version 4 Pro). Use this to get strategic directions, break down problems, or create detailed plans.",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        name = "task_details",
                        description = "The specific complex task, problem, or objective that needs planning, reasoning, or direction",
                        type = ToolParameterType.String
                    )
                ),
                optionalParameters = listOf(
                    ToolParameterDescriptor(
                        name = "context",
                        description = "Additional background information or constraint details to help the planning model",
                        type = ToolParameterType.String
                    )
                )
            )
        ) + mcpTools
    }

    private fun buildConversationPrompt(
        messages: List<CoreChatMessage>,
        reasoningEnabled: Boolean
    ): Prompt {
        val params = if (reasoningEnabled) {
            LLMParams(
                additionalProperties = mapOf(
                    "reasoning" to JsonObject(mapOf("effort" to JsonPrimitive("low")))
                )
            )
        } else {
            LLMParams()
        }

        return prompt("alfred-openrouter-chat", params) {
            system(prompt)

            for (msg in messages.filter { it.includeInPrompt }) {
                when (msg.kind) {
                    CoreChatMessageKind.REASONING -> {
                        val content = msg.reasoningText ?: msg.content
                        val summary = msg.reasoningSummary
                        if (!content.isNullOrBlank()) {
                            reasoning(content, summary.orEmpty())
                        }
                    }
                    CoreChatMessageKind.TOOL_CALL -> {
                        val name = msg.toolName
                        val args = msg.toolArgumentsJson ?: msg.content
                        if (!name.isNullOrBlank()) {
                            toolCall(name, args, msg.toolCallId.orEmpty())
                        }
                    }
                    CoreChatMessageKind.TOOL_RESULT -> {
                        val name = msg.toolName
                        if (!name.isNullOrBlank()) {
                            toolResult(name, msg.content, msg.toolCallId.orEmpty(), msg.isError)
                        }
                    }
                    CoreChatMessageKind.MESSAGE -> {
                        when (msg.role) {
                            "user" -> user(msg.content)
                            "assistant" -> assistant(msg.content)
                        }
                    }
                }
            }
        }
    }

    private suspend fun streamSingleCompletionWithReasoningFallback(
        client: OpenRouterLLMClient,
        model: LLModel,
        messages: List<CoreChatMessage>,
        tools: List<ToolDescriptor>,
        passIndex: Int,
        reasoningEnabled: Boolean,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ): PassWithPromptState {
        val prompt = buildConversationPrompt(messages, reasoningEnabled)
        return try {
            PassWithPromptState(
                result = streamSingleCompletion(client, model, prompt, tools, passIndex, onEvent),
                reasoningEnabled = reasoningEnabled
            )
        } catch (error: Exception) {
            if (!reasoningEnabled) {
                throw error
            }
            val fallbackPrompt = buildConversationPrompt(messages, reasoningEnabled = false)
            PassWithPromptState(
                result = streamSingleCompletion(client, model, fallbackPrompt, tools, passIndex, onEvent),
                reasoningEnabled = false
            )
        }
    }

    private suspend fun streamSingleCompletion(
        client: OpenRouterLLMClient,
        model: LLModel,
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        passIndex: Int,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ): StreamPassResult {
        val content = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val reasoningParts = mutableListOf<ReasoningPart>()
        val reasoningTextById = linkedMapOf<String, StringBuilder>()
        val reasoningSummaryById = linkedMapOf<String, StringBuilder>()
        val toolCallArgsByKey = linkedMapOf<String, StringBuilder>()
        val toolCallNamesByKey = mutableMapOf<String, String>()
        val toolCallIdsByKey = mutableMapOf<String, String?>()
        val toolCallIdByIndex = mutableMapOf<Int, String>()
        var completedText: String? = null
        var emittedAnyFrame = false

        client.executeStreaming(prompt, model, tools).collect { frame ->
            emittedAnyFrame = true
            when (frame) {
                is StreamFrame.TextDelta -> {
                    content.append(frame.text)
                    onEvent(ChatStreamEvent.TextDelta(passIndex, frame.text))
                }

                is StreamFrame.TextComplete -> {
                    completedText = frame.text
                }

                is StreamFrame.ReasoningDelta -> {
                    val key = "reasoning-$passIndex"
                    frame.text?.let { reasoningTextById.getOrPut(key) { StringBuilder() }.append(it) }
                    frame.summary?.let { reasoningSummaryById.getOrPut(key) { StringBuilder() }.append(it) }
                    onEvent(
                        ChatStreamEvent.ReasoningDelta(
                            passIndex = passIndex,
                            id = key,
                            textChunk = frame.text,
                            summaryChunk = frame.summary
                        )
                    )
                }

                is StreamFrame.ReasoningComplete -> {
                    val key = "reasoning-$passIndex"
                    val content = preferFullerReasoning(
                        completedParts = frame.content.orEmpty(),
                        streamedText = reasoningTextById[key]?.toString()
                    )
                    val summary = preferFullerReasoning(
                        completedParts = frame.summary.orEmpty(),
                        streamedText = reasoningSummaryById[key]?.toString()
                    )
                    val newPart = ReasoningPart(
                        id = key,
                        content = content,
                        summary = summary,
                        encrypted = frame.encrypted
                    )
                    val existingIndex = reasoningParts.indexOfFirst { it.id == key }
                    if (existingIndex >= 0) {
                        reasoningParts[existingIndex] = newPart
                    } else {
                        reasoningParts.add(newPart)
                    }
                    onEvent(
                        ChatStreamEvent.ReasoningComplete(
                            passIndex = passIndex,
                            id = key,
                            content = content,
                            summary = summary,
                            encrypted = frame.encrypted
                        )
                    )
                }

                is StreamFrame.ToolCallDelta -> {
                    val index = frame.index ?: 0
                    val frameId = frame.id
                    if (frameId != null) {
                        toolCallIdByIndex[index] = frameId
                    }
                    val stableId = toolCallIdByIndex[index] ?: frameId ?: "tool-$index"
                    val key = "tool-$passIndex-$index"
                    frame.name?.takeIf { it.isNotBlank() }?.let { toolCallNamesByKey[key] = it }
                    toolCallIdsByKey[key] = stableId
                    toolCallArgsByKey.getOrPut(key) { StringBuilder() }.append(frame.content)
                    onEvent(
                        ChatStreamEvent.ToolCallDelta(
                            passIndex = passIndex,
                            id = stableId,
                            name = frame.name,
                            argumentsChunk = frame.content.orEmpty()
                        )
                    )
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

        val completedReasoningKeys = reasoningParts.mapNotNull { it.id }.toSet()
        reasoningTextById.forEach { (key, text) ->
            if (key !in completedReasoningKeys) {
                reasoningParts.add(
                    ReasoningPart(
                        id = key,
                        content = listOf(text.toString()),
                        summary = reasoningSummaryById[key]?.toString()?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                        encrypted = null
                    )
                )
            }
        }
        toolCallArgsByKey.forEach { (key, args) ->
            if (toolCalls.none { it.id == toolCallIdsByKey[key] } && toolCallNamesByKey[key] != null) {
                toolCalls.add(
                    ToolCall(
                        id = toolCallIdsByKey[key],
                        name = toolCallNamesByKey.getValue(key),
                        argumentsJson = args.toString()
                    )
                )
            }
        }

        return StreamPassResult(
            content = completedText ?: content.toString(),
            toolCalls = toolCalls,
            reasoning = reasoningParts,
            emittedAnyFrame = emittedAnyFrame
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

            ASK_SMART_MODEL_FUNCTION_NAME -> {
                val details = extractSmartModelDelegationDetails(arguments)
                if (details == null) {
                    "Smart model delegation failed: missing required 'task_details' argument."
                } else {
                    try {
                        createOpenRouterClient().use { client ->
                            executeSmartModelDelegation(client, details)
                        }
                    } catch (e: Exception) {
                        "Smart model delegation failed: ${e.message}"
                    }
                }
            }

            else -> {
                val client = tickTickClient
                if (client == null) {
                    "TickTick integration is not configured. Please configure your TickTick credentials in settings first."
                } else {
                    try {
                        client.executeMcpToolCall(functionName, arguments)
                    } catch (e: Exception) {
                        "TickTick failed: ${e.message}"
                    }
                }
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

    data class SmartModelDelegationDetails(
        val taskDetails: String,
        val context: String?
    )

    internal fun extractSmartModelDelegationDetails(argumentsJson: String): SmartModelDelegationDetails? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val taskDetails = node.path("task_details").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val context = node.path("context").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
            SmartModelDelegationDetails(taskDetails, context)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeSmartModelDelegation(
        client: OpenRouterLLMClient,
        details: SmartModelDelegationDetails
    ): String {
        val smartModel = LLModel(
            provider = LLMProvider.OpenRouter,
            id = "deepseek/deepseek-v4-pro",
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature
            )
        )
        val delegationPrompt = prompt("smart-model-delegation") {
            system("You are a super-intelligent reasoning model. Your task is to provide detailed, structured instructions, direction, and step-by-step guidance to help another AI agent execute a complex task. Focus on clarity, logical progression, edge cases, and best practices. Do not execute the task yourself, but write a precise plan.")
            user(
                buildString {
                    append("Task Details:\n")
                    append(details.taskDetails)
                    if (!details.context.isNullOrBlank()) {
                        append("\n\nAdditional Context:\n")
                        append(details.context)
                    }
                }
            )
        }

        val resultText = StringBuilder()
        client.executeStreaming(delegationPrompt, smartModel, emptyList()).collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> {
                    resultText.append(frame.text)
                }
                is StreamFrame.TextComplete -> {
                    if (frame.text.isNotBlank()) {
                        resultText.clear().append(frame.text)
                    }
                }
                else -> Unit
            }
        }
        val finalResult = resultText.toString()
        if (finalResult.isBlank()) {
            throw Exception("Empty response from smart model")
        }
        return finalResult
    }

    internal fun preferFullerReasoning(completedParts: List<String>, streamedText: String?): List<String> {
        val streamed = streamedText?.takeIf { it.isNotBlank() } ?: return completedParts
        val completedText = completedParts.joinToString("\n")
        if (completedText.isBlank()) {
            return listOf(streamed)
        }

        val streamedComparable = streamed.trim()
        val completedComparable = completedText.trim()
        return if (streamedComparable.length > completedComparable.length) {
            listOf(streamed)
        } else {
            completedParts
        }
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.5-flash"
        const val WEB_SEARCH_FUNCTION_NAME = "WebSearchTool"
        const val LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME = "SearchLocalKnowledgeTool"
        const val TICKTICK_FUNCTION_NAME = "TickTickTool"
        const val ASK_SMART_MODEL_FUNCTION_NAME = "AskSmartModelTool"
        private const val MAX_AGENT_PASSES = 6
    }
}

private object EmptyLocalKnowledgeSearchClient : LocalKnowledgeSearchClient {
    override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> {
        return Result.success(emptyList())
    }
}
