package com.aibyjohannes.alfred.core.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.http.client.KoogHttpClient
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
import com.aibyjohannes.alfred.core.github.GitHubMcpClient
import com.aibyjohannes.alfred.core.notion.NotionMcpClient
import com.aibyjohannes.alfred.core.openrouter.OpenRouterAttribution
import com.aibyjohannes.alfred.core.reminders.ReminderClient
import com.aibyjohannes.alfred.core.reminders.ReminderRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchRequest
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchResult
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSource
import com.aibyjohannes.alfred.core.search.ObsidianClient
import com.aibyjohannes.alfred.core.search.ObsidianCliEmulator
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.aibyjohannes.alfred.core.skills.SkillClient
import com.aibyjohannes.alfred.core.skills.SkillSummary
import com.aibyjohannes.alfred.core.ticktick.TickTickClient
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.security.MessageDigest

class OpenRouterChatEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val prompt: String = SystemPrompts.SYSTEM_PROMPT,
    private val webSearchClient: WebSearchClient,
    private val localKnowledgeSearchClient: LocalKnowledgeSearchClient? = null,
    private val tickTickClient: TickTickClient? = null,
    private val notionMcpClient: NotionMcpClient? = null,
    private val githubMcpClient: GitHubMcpClient? = null,
    private val imageGenerator: (suspend (String) -> Result<String>)? = null,
    private val obsidianClient: ObsidianClient? = null,
    private val skillClient: SkillClient? = null,
    private val reminderClient: ReminderClient? = null,
    private val maxAgentPasses: Int = 10,
    private val efficiencyModeEnabled: Boolean = false,
    private val privacyModeEnabled: Boolean = false,
    private val toolsEnabled: Boolean = true,
    sessionId: String? = null
) : ChatEngine {
    private val objectMapper = ObjectMapper()
    private val openRouterSessionId = sessionId?.takeIf { it.isNotBlank() }?.let(::hashedSessionId)
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
                val mcpTools = if (toolsEnabled) {
                    (tickTickClient?.getMcpTools() ?: emptyList()) +
                        (notionMcpClient?.getMcpTools() ?: emptyList()) +
                        (githubMcpClient?.getMcpTools() ?: emptyList())
                } else emptyList()
                val skills = if (toolsEnabled) skillClient?.listSkills()?.getOrDefault(emptyList()).orEmpty() else emptyList()
                val tools = if (toolsEnabled) buildAlfredToolDescriptors(mcpTools, skills.isNotEmpty()) else emptyList()
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
                val generatedImageWidgets = mutableListOf<String>()
                var reasoningEnabled = true
                var finalContent: String? = null
                var passIndex = 0

                while (passIndex < maxAgentPasses && finalContent == null) {
                    send(ChatStreamEvent.PassStarted(passIndex))
                    val pass = streamSingleCompletionWithReasoningFallback(
                        client = client,
                        model = koogModel,
                        messages = promptMessages,
                        tools = tools,
                        passIndex = passIndex,
                        reasoningEnabled = reasoningEnabled,
                        skills = skills,
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
                            result.startsWith("Notion failed", ignoreCase = true) ||
                            result.startsWith("GitHub failed", ignoreCase = true) ||
                            result.startsWith("Image generation failed", ignoreCase = true) ||
                            result.startsWith("TickTick integration is not configured", ignoreCase = true) ||
                            result.startsWith("Reminder failed", ignoreCase = true) ||
                            result.startsWith("Reminder scheduling is not configured", ignoreCase = true) ||
                            result.startsWith("Error:", ignoreCase = true) ||
                            result.startsWith("Unknown TickTick action", ignoreCase = true) ||
                            result.startsWith("Smart model delegation failed", ignoreCase = true) ||
                            result.startsWith("Obsidian search failed", ignoreCase = true) ||
                            result.startsWith("Obsidian read failed", ignoreCase = true) ||
                            result.startsWith("Obsidian write failed", ignoreCase = true) ||
                            result.startsWith("Obsidian create failed", ignoreCase = true) ||
                            result.startsWith("Obsidian update failed", ignoreCase = true) ||
                            result.startsWith("Obsidian rename failed", ignoreCase = true) ||
                            result.startsWith("Obsidian delete failed", ignoreCase = true) ||
                            result.startsWith("Obsidian list folder failed", ignoreCase = true) ||
                            result.startsWith("Obsidian integration is not configured", ignoreCase = true) ||
                            result.startsWith("Skill read failed", ignoreCase = true) ||
                            result.startsWith("Skill reference read failed", ignoreCase = true)
                        if (toolCall.name == GENERATE_IMAGE_TOOL && !isError) {
                            generatedImageWidgets += result
                        }
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

                val baseContent = finalContent ?: "Agent loop limit reached. I executed $maxAgentPasses passes without producing a final response."
                val content = buildString {
                    append(baseContent)
                    generatedImageWidgets.filterNot(baseContent::contains).forEach { widget ->
                        append("\n\n")
                        append(widget)
                    }
                }
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
        return OpenRouterLLMClient(apiKey, OpenRouterClientSettings(), OpenRouterAttributionHttpClientFactory())
    }

    private class OpenRouterAttributionHttpClientFactory(
        private val delegate: KoogHttpClient.Factory = KtorKoogHttpClient.Factory()
    ) : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json
        ): KoogHttpClient = delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers + OpenRouterAttribution.headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json
        )
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

    internal fun buildAlfredToolDescriptors(
        mcpTools: List<ToolDescriptor> = emptyList()
    ): List<ToolDescriptor> = buildAlfredToolDescriptors(mcpTools, skillsAvailable = false)

    internal fun buildAlfredToolDescriptors(
        mcpTools: List<ToolDescriptor>,
        skillsAvailable: Boolean = false
    ): List<ToolDescriptor> {
        val alfredTools = mutableListOf(
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
                name = CREATE_PLAN_FUNCTION_NAME,
                description = "Create a detailed execution plan for a complex task with a stronger reasoning model. Use this for strategic planning, multi-step directions, or troubleshooting guidance.",
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
            ),
            ToolDescriptor(
                name = SCHEDULE_REMINDER_TOOL,
                description = "Schedule a one-time local Alfred notification. Use only when the user explicitly asks for a reminder. The scheduled_at value must be a future ISO-8601 timestamp with an explicit UTC offset, based on the system context.",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        name = "message",
                        description = "Short reminder text shown in the notification",
                        type = ToolParameterType.String
                    ),
                    ToolParameterDescriptor(
                        name = "scheduled_at",
                        description = "Future ISO-8601 date-time with offset, for example 2026-07-10T09:00:00+02:00",
                        type = ToolParameterType.String
                    )
                )
            )
        )

        if (imageGenerator != null) {
            alfredTools.add(
                ToolDescriptor(
                    name = GENERATE_IMAGE_TOOL,
                    description = "Generate an image from a text prompt and attach it to this chat. Call this whenever the user asks to create or generate an image.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "prompt",
                            description = "A complete, detailed description of the image to generate",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
        }

        if (obsidianClient != null) {
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_CLI_TOOL,
                    description = "Run one safe Obsidian CLI-style command against the user-selected vault. Commands must start with 'obsidian' and support only read, search, files, folders, create, write, append, rename, move, and delete. This is not a system shell.",
                    requiredParameters = listOf(ToolParameterDescriptor("command", "One Obsidian-style command, e.g. obsidian read path=\"Projects/Alfred.md\"", ToolParameterType.String))
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_SEARCH_TOOL,
                    description = "Search for notes in the Obsidian vault by matching keywords in filenames or note content. Results include the modification date of each file.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "query",
                            description = "The search query/terms to look up in the notes",
                            type = ToolParameterType.String
                        )
                    ),
                    optionalParameters = listOf(
                        ToolParameterDescriptor(
                            name = "directory",
                            description = "Limit search to this sub-folder path within the vault (e.g. 'Projects/2025'). Omit to search the entire vault.",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "sort_by",
                            description = "How to sort results: 'score' (relevance, default), 'modified' (modification date), or 'filename'.",
                            type = ToolParameterType.Enum(arrayOf("score", "modified", "filename"))
                        ),
                        ToolParameterDescriptor(
                            name = "order",
                            description = "Sort direction: 'desc' (default, highest/newest first) or 'asc' (lowest/oldest first).",
                            type = ToolParameterType.Enum(arrayOf("desc", "asc"))
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_LIST_FOLDER_TOOL,
                    description = "List the immediate contents (subfolders and notes) of a folder in the Obsidian vault with modification dates. Use this to browse the vault structure folder by folder.",
                    requiredParameters = emptyList(),
                    optionalParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative folder path to list (e.g. 'Daily' or 'Projects/2025'). Leave empty to list the vault root.",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_READ_TOOL,
                    description = "Read the full content of an Obsidian note by its relative path.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative path of the note within the vault (e.g. 'Project/Planning.md')",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_CREATE_TOOL,
                    description = "Create a new Obsidian note at the specified relative .md path. Fails if the note already exists.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative .md path of the new note within the vault (e.g. 'Project/Planning.md')",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "content",
                            description = "The initial text content to write to the note",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_UPDATE_TOOL,
                    description = "Update an existing Obsidian note by overwriting or appending content. Fails if the note does not exist.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative .md path of the existing note within the vault (e.g. 'Project/Planning.md')",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "content",
                            description = "The text content to write or append to the note",
                            type = ToolParameterType.String
                        )
                    ),
                    optionalParameters = listOf(
                        ToolParameterDescriptor(
                            name = "append",
                            description = "If true, appends the content to the end of the note instead of overwriting. Defaults to false.",
                            type = ToolParameterType.Boolean
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_RENAME_TOOL,
                    description = "Rename or move an Obsidian note from one relative .md path to another. Fails if the destination already exists.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "from_path",
                            description = "The current relative .md path of the note",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "to_path",
                            description = "The destination relative .md path for the note",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_DELETE_TOOL,
                    description = "Hard-delete an Obsidian note by its relative .md path.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative .md path of the note to delete",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = OBSIDIAN_WRITE_TOOL,
                    description = "Legacy alias for updating an existing Obsidian note at the specified relative path.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "path",
                            description = "The relative path of the note within the vault (e.g. 'Project/Planning.md')",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "content",
                            description = "The text content to write or append to the note",
                            type = ToolParameterType.String
                        )
                    ),
                    optionalParameters = listOf(
                        ToolParameterDescriptor(
                            name = "append",
                            description = "If true, appends the content to the end of the note instead of overwriting. Defaults to false.",
                            type = ToolParameterType.Boolean
                        )
                    )
                )
            )
        }

        if (skillClient != null) {
            alfredTools.add(
                ToolDescriptor(
                    name = CREATE_SKILL_TOOL,
                    description = "Create a new Agent Skill under Alfred storage with a generated SKILL.md. Fails if the skill already exists.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "skill_id",
                            description = "Lowercase hyphenated skill id, matching the new skill folder name",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "description",
                            description = "Skill description explaining what the skill does and when to use it",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "instructions",
                            description = "Markdown instructions for the SKILL.md body",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = RENAME_SKILL_TOOL,
                    description = "Rename an existing Agent Skill folder and update the SKILL.md name frontmatter. Preserves bundled files.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "from_skill_id",
                            description = "Current skill id",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "to_skill_id",
                            description = "New lowercase hyphenated skill id",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = WRITE_SKILL_REFERENCE_TOOL,
                    description = "Create or update a Markdown or text reference file inside an existing skill directory.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "skill_id",
                            description = "The skill id from the available skills catalog",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "path",
                            description = "Forward-slash relative .md or .txt path inside the skill directory",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "content",
                            description = "Reference file content",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = DELETE_SKILL_REFERENCE_TOOL,
                    description = "Delete an existing Markdown or text reference file after the user explicitly asks for deletion.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor("skill_id", "The skill id", ToolParameterType.String),
                        ToolParameterDescriptor("path", "Forward-slash relative reference path", ToolParameterType.String)
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = MOVE_SKILL_REFERENCE_TOOL,
                    description = "Move an existing Markdown or text reference file within a skill after the user explicitly asks.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor("skill_id", "The skill id", ToolParameterType.String),
                        ToolParameterDescriptor("from_path", "Current relative reference path", ToolParameterType.String),
                        ToolParameterDescriptor("to_path", "Destination relative reference path", ToolParameterType.String)
                    )
                )
            )
        }

        if (skillsAvailable && skillClient != null) {
            alfredTools.add(
                ToolDescriptor(
                    name = READ_SKILL_TOOL,
                    description = "Read the complete SKILL.md instructions for an available skill before applying it.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "skill_id",
                            description = "The skill id from the available skills catalog",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
            alfredTools.add(
                ToolDescriptor(
                    name = READ_SKILL_REFERENCE_TOOL,
                    description = "Read a Markdown or text reference within a previously selected skill directory.",
                    requiredParameters = listOf(
                        ToolParameterDescriptor(
                            name = "skill_id",
                            description = "The skill id from the available skills catalog",
                            type = ToolParameterType.String
                        ),
                        ToolParameterDescriptor(
                            name = "path",
                            description = "Forward-slash relative path named by the skill instructions",
                            type = ToolParameterType.String
                        )
                    )
                )
            )
        }

        return alfredTools + mcpTools
    }

    private fun buildConversationPrompt(
        messages: List<CoreChatMessage>,
        reasoningEnabled: Boolean,
        skills: List<SkillSummary>
    ): Prompt {
        val params = LLMParams(additionalProperties = requestAdditionalProperties(reasoningEnabled))

        return prompt("alfred-openrouter-chat", params) {
            system(prompt)
            if (skills.isNotEmpty()) {
                system(formatSkillsCatalog(skills))
            }

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
        skills: List<SkillSummary>,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ): PassWithPromptState {
        val prompt = buildConversationPrompt(messages, reasoningEnabled, skills)
        return try {
            PassWithPromptState(
                result = streamSingleCompletion(client, model, prompt, tools, passIndex, onEvent),
                reasoningEnabled = reasoningEnabled
            )
        } catch (error: Exception) {
            if (!reasoningEnabled) {
                throw error
            }
            val fallbackPrompt = buildConversationPrompt(messages, reasoningEnabled = false, skills = skills)
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

    internal suspend fun executeToolCall(functionName: String, rawArguments: String): String {
        val arguments = normalizeToolArguments(rawArguments)
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

            CREATE_PLAN_FUNCTION_NAME,
            LEGACY_ASK_SMART_MODEL_FUNCTION_NAME -> {
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

            SCHEDULE_REMINDER_TOOL -> {
                val request = extractReminderRequest(arguments)
                if (request == null) {
                    "Reminder failed: provide a non-empty 'message' and a future ISO-8601 'scheduled_at' value with an explicit offset."
                } else {
                    reminderClient?.scheduleReminder(request)?.fold(
                        onSuccess = { it },
                        onFailure = { "Reminder failed: ${it.message}" }
                    ) ?: "Reminder scheduling is not configured."
                }
            }

            GENERATE_IMAGE_TOOL -> {
                val imagePrompt = runCatching {
                    objectMapper.readTree(arguments).path("prompt").asText(null)?.trim()?.takeIf(String::isNotEmpty)
                }.getOrNull()
                if (imagePrompt == null) {
                    "Image generation failed: missing required 'prompt' argument."
                } else {
                    imageGenerator?.invoke(imagePrompt)?.fold(
                        onSuccess = { it },
                        onFailure = { "Image generation failed: ${it.message ?: "Unknown error"}" }
                    ) ?: "Image generation failed: image generation is not configured."
                }
            }

            OBSIDIAN_CLI_TOOL -> {
                val command = runCatching { objectMapper.readTree(arguments).path("command").asText() }.getOrNull()
                if (command.isNullOrBlank()) "Obsidian CLI failed: missing required 'command' argument."
                else obsidianClient?.let { ObsidianCliEmulator(it).execute(command) }?.fold(
                    onSuccess = { it }, onFailure = { "Obsidian CLI failed: ${it.message}" }
                ) ?: "Obsidian integration is not configured."
            }

            OBSIDIAN_SEARCH_TOOL -> {
                val args = extractObsidianSearchArgs(arguments)
                if (args == null || args.query.isBlank()) {
                    "Obsidian search failed: missing required 'query' argument."
                } else {
                    obsidianClient?.search(
                        query = args.query,
                        directory = args.directory,
                        sortBy = args.sortBy,
                        order = args.order
                    )?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian search failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_LIST_FOLDER_TOOL -> {
                val path = extractObsidianFolderPath(arguments)
                obsidianClient?.listFolder(path ?: "")?.fold(
                    onSuccess = { it },
                    onFailure = { "Obsidian list folder failed: ${it.message}" }
                ) ?: "Obsidian integration is not configured."
            }

            OBSIDIAN_READ_TOOL -> {
                val path = extractObsidianReadPath(arguments)
                if (path.isNullOrBlank()) {
                    "Obsidian read failed: missing required 'path' argument."
                } else {
                    obsidianClient?.read(path)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian read failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_CREATE_TOOL -> {
                val request = extractObsidianWriteRequest(arguments)
                if (request == null || request.path.isBlank() || request.content.isBlank()) {
                    "Obsidian create failed: missing required 'path' or 'content' argument."
                } else {
                    obsidianClient?.create(request.path, request.content)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian create failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_UPDATE_TOOL -> {
                val request = extractObsidianWriteRequest(arguments)
                if (request == null || request.path.isBlank() || request.content.isBlank()) {
                    "Obsidian update failed: missing required 'path' or 'content' argument."
                } else {
                    obsidianClient?.update(request.path, request.content, request.append)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian update failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_RENAME_TOOL -> {
                val request = extractObsidianRenameRequest(arguments)
                if (request == null || request.fromPath.isBlank() || request.toPath.isBlank()) {
                    "Obsidian rename failed: missing required 'from_path' or 'to_path' argument."
                } else {
                    obsidianClient?.rename(request.fromPath, request.toPath)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian rename failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_DELETE_TOOL -> {
                val path = extractObsidianReadPath(arguments)
                if (path.isNullOrBlank()) {
                    "Obsidian delete failed: missing required 'path' argument."
                } else {
                    obsidianClient?.delete(path)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian delete failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            OBSIDIAN_WRITE_TOOL -> {
                val request = extractObsidianWriteRequest(arguments)
                if (request == null || request.path.isBlank() || request.content.isBlank()) {
                    "Obsidian write failed: missing required 'path' or 'content' argument."
                } else {
                    obsidianClient?.update(request.path, request.content, request.append)?.fold(
                        onSuccess = { it },
                        onFailure = { "Obsidian write failed: ${it.message}" }
                    ) ?: "Obsidian integration is not configured."
                }
            }

            READ_SKILL_TOOL -> {
                val skillId = extractSkillId(arguments)
                if (skillId == null) {
                    "Skill read failed: missing required 'skill_id' argument."
                } else {
                    skillClient?.readSkill(skillId)?.fold(
                        onSuccess = { it },
                        onFailure = { "Skill read failed: ${it.message}" }
                    ) ?: "Skill read failed: skill support is not configured."
                }
            }

            READ_SKILL_REFERENCE_TOOL -> {
                val request = extractSkillReferenceRequest(arguments)
                if (request == null) {
                    "Skill reference read failed: missing required 'skill_id' or 'path' argument."
                } else {
                    skillClient?.readReference(request.skillId, request.path)?.fold(
                        onSuccess = { it },
                        onFailure = { "Skill reference read failed: ${it.message}" }
                    ) ?: "Skill reference read failed: skill support is not configured."
                }
            }

            CREATE_SKILL_TOOL -> {
                val request = extractSkillCreateRequest(arguments)
                if (request == null) {
                    "Skill create failed: missing required 'skill_id', 'description', or 'instructions' argument."
                } else {
                    skillClient?.createSkill(request.skillId, request.description, request.instructions)?.fold(
                        onSuccess = { it },
                        onFailure = { "Skill create failed: ${it.message}" }
                    ) ?: "Skill create failed: skill support is not configured."
                }
            }

            RENAME_SKILL_TOOL -> {
                val request = extractSkillRenameRequest(arguments)
                if (request == null) {
                    "Skill rename failed: missing required 'from_skill_id' or 'to_skill_id' argument."
                } else {
                    skillClient?.renameSkill(request.fromSkillId, request.toSkillId)?.fold(
                        onSuccess = { it },
                        onFailure = { "Skill rename failed: ${it.message}" }
                    ) ?: "Skill rename failed: skill support is not configured."
                }
            }

            WRITE_SKILL_REFERENCE_TOOL -> {
                val request = extractSkillReferenceWriteRequest(arguments)
                if (request == null) {
                    "Skill reference write failed: missing required 'skill_id', 'path', or 'content' argument."
                } else {
                    skillClient?.writeReference(request.skillId, request.path, request.content)?.fold(
                        onSuccess = { it },
                        onFailure = { "Skill reference write failed: ${it.message}" }
                    ) ?: "Skill reference write failed: skill support is not configured."
                }
            }

            DELETE_SKILL_REFERENCE_TOOL -> {
                val request = extractSkillReferenceRequest(arguments)
                if (request == null) "Skill reference delete failed: missing required 'skill_id' or 'path' argument."
                else skillClient?.deleteReference(request.skillId, request.path)?.fold(
                    onSuccess = { it }, onFailure = { "Skill reference delete failed: ${it.message}" }
                ) ?: "Skill reference delete failed: skill support is not configured."
            }

            MOVE_SKILL_REFERENCE_TOOL -> {
                val request = extractSkillReferenceMoveRequest(arguments)
                if (request == null) "Skill reference move failed: missing required 'skill_id', 'from_path', or 'to_path' argument."
                else skillClient?.moveReference(request.skillId, request.fromPath, request.toPath)?.fold(
                    onSuccess = { it }, onFailure = { "Skill reference move failed: ${it.message}" }
                ) ?: "Skill reference move failed: skill support is not configured."
            }

            else -> {
                if (githubMcpClient?.ownsTool(functionName) == true) {
                    try {
                        githubMcpClient.executeMcpToolCall(functionName, arguments)
                    } catch (e: Exception) {
                        "GitHub failed: ${e.message}"
                    }
                } else if (notionMcpClient?.ownsTool(functionName) == true) {
                    try {
                        notionMcpClient.executeMcpToolCall(functionName, arguments)
                    } catch (e: Exception) {
                        "Notion failed: ${e.message}"
                    }
                } else {
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
    }

    internal fun formatSkillsCatalog(skills: List<SkillSummary>): String = buildString {
        appendLine("Available skills:")
        skills.forEach { skill ->
            appendLine("- ${skill.id}: ${skill.description}")
        }
        appendLine()
        append("When a request clearly matches a skill, call $READ_SKILL_TOOL before answering. ")
        append("Read only references named by those instructions with $READ_SKILL_REFERENCE_TOOL. ")
        append("Use $CREATE_SKILL_TOOL, $RENAME_SKILL_TOOL, $WRITE_SKILL_REFERENCE_TOOL, $DELETE_SKILL_REFERENCE_TOOL, or $MOVE_SKILL_REFERENCE_TOOL only when the user asks to manage skill files.")
    }.trim()

    internal fun extractSkillId(argumentsJson: String): String? {
        return try {
            objectMapper.readTree(argumentsJson)
                .path("skill_id")
                .asText(null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    data class SkillReferenceRequest(val skillId: String, val path: String)
    data class SkillCreateRequest(val skillId: String, val description: String, val instructions: String)
    data class SkillRenameRequest(val fromSkillId: String, val toSkillId: String)
    data class SkillReferenceWriteRequest(val skillId: String, val path: String, val content: String)
    data class SkillReferenceMoveRequest(val skillId: String, val fromPath: String, val toPath: String)

    internal fun extractSkillReferenceRequest(argumentsJson: String): SkillReferenceRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val skillId = node.path("skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val path = node.path("path").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            SkillReferenceRequest(skillId, path)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractSkillCreateRequest(argumentsJson: String): SkillCreateRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val skillId = node.path("skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val description = node.path("description").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val instructions = node.path("instructions").asText(null) ?: return null
            SkillCreateRequest(skillId, description, instructions)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractSkillRenameRequest(argumentsJson: String): SkillRenameRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val fromSkillId = node.path("from_skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val toSkillId = node.path("to_skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            SkillRenameRequest(fromSkillId, toSkillId)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractSkillReferenceWriteRequest(argumentsJson: String): SkillReferenceWriteRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val skillId = node.path("skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val path = node.path("path").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val content = node.path("content").asText(null) ?: return null
            SkillReferenceWriteRequest(skillId, path, content)
        } catch (_: Exception) {
            null
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

    internal fun extractSkillReferenceMoveRequest(argumentsJson: String): SkillReferenceMoveRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val skillId = node.path("skill_id").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
            val from = node.path("from_path").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
            val to = node.path("to_path").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
            if (skillId == null || from == null || to == null) null else SkillReferenceMoveRequest(skillId, from, to)
        } catch (_: Exception) { null }
    }

    /** Accept a common model mistake: wrapping a tool's input in a result envelope. */
    fun normalizeToolArguments(rawArguments: String): String = try {
        val node = objectMapper.readTree(rawArguments)
        if (!node.isObject || node.size() != 1 || !node.has("result")) rawArguments else {
            val result = node.path("result")
            when {
                result.isObject -> objectMapper.writeValueAsString(result)
                result.isTextual && result.asText().trim().startsWith("{") -> result.asText()
                else -> rawArguments
            }
        }
    } catch (_: Exception) { rawArguments }

    fun requestAdditionalProperties(reasoningEnabled: Boolean): Map<String, JsonElement> = buildMap {
        if (reasoningEnabled) {
            put("reasoning", JsonObject(mapOf("effort" to JsonPrimitive("low"))))
        }
        if (model == DEFAULT_MODEL) {
            put("models", JsonArray(listOf(JsonPrimitive(FALLBACK_MODEL))))
        }
        if (privacyModeEnabled) {
            put("provider", JsonObject(mapOf("zdr" to JsonPrimitive(true))))
        }
        if (efficiencyModeEnabled && openRouterSessionId != null) {
            put("session_id", JsonPrimitive(openRouterSessionId))
        }
    }

    private fun hashedSessionId(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "alfred-$digest"
    }

    internal fun extractReminderRequest(argumentsJson: String): ReminderRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val message = node.path("message").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val scheduledAt = node.path("scheduled_at").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val triggerAtEpochMs = OffsetDateTime.parse(scheduledAt).toInstant().toEpochMilli()
            if (triggerAtEpochMs <= System.currentTimeMillis()) return null
            ReminderRequest(message, triggerAtEpochMs)
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

    data class ObsidianWriteRequest(
        val path: String,
        val content: String,
        val append: Boolean
    )

    data class ObsidianRenameRequest(
        val fromPath: String,
        val toPath: String
    )

    data class ObsidianSearchArgs(
        val query: String,
        val directory: String?,
        val sortBy: String,
        val order: String
    )

    internal fun extractObsidianSearchArgs(argumentsJson: String): ObsidianSearchArgs? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val query = node.path("query").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val directory = node.path("directory").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
            val sortBy = node.path("sort_by").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: "score"
            val order = node.path("order").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: "desc"
            ObsidianSearchArgs(query, directory, sortBy, order)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractObsidianFolderPath(argumentsJson: String): String? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            node.path("path").asText(null)?.trim()
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractObsidianReadPath(argumentsJson: String): String? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            node.path("path").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractObsidianWriteRequest(argumentsJson: String): ObsidianWriteRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val path = node.path("path").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val content = node.path("content").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val append = node.path("append").asBoolean(false)
            ObsidianWriteRequest(path, content, append)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractObsidianRenameRequest(argumentsJson: String): ObsidianRenameRequest? {
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val fromPath = node.path("from_path").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val toPath = node.path("to_path").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ObsidianRenameRequest(fromPath, toPath)
        } catch (_: Exception) {
            null
        }
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
        const val DEFAULT_MODEL = "openai/gpt-5.6-luna"
        const val FALLBACK_MODEL = "google/gemma-4-26b-a4b-it"
        const val WEB_SEARCH_FUNCTION_NAME = "WebSearchTool"
        const val LOCAL_KNOWLEDGE_SEARCH_FUNCTION_NAME = "SearchLocalKnowledgeTool"
        const val TICKTICK_FUNCTION_NAME = "TickTickTool"
        const val CREATE_PLAN_FUNCTION_NAME = "CreatePlanTool"
        const val LEGACY_ASK_SMART_MODEL_FUNCTION_NAME = "AskSmartModelTool"
        const val SCHEDULE_REMINDER_TOOL = "ScheduleReminderTool"
        const val GENERATE_IMAGE_TOOL = "GenerateImageTool"
        const val OBSIDIAN_CLI_TOOL = "RunObsidianCliTool"
        const val OBSIDIAN_SEARCH_TOOL = "SearchObsidianVaultTool"
        const val OBSIDIAN_LIST_FOLDER_TOOL = "ListObsidianFolderTool"
        const val OBSIDIAN_READ_TOOL = "ReadObsidianNoteTool"
        const val OBSIDIAN_CREATE_TOOL = "CreateObsidianNoteTool"
        const val OBSIDIAN_UPDATE_TOOL = "UpdateObsidianNoteTool"
        const val OBSIDIAN_RENAME_TOOL = "RenameObsidianNoteTool"
        const val OBSIDIAN_DELETE_TOOL = "DeleteObsidianNoteTool"
        const val OBSIDIAN_WRITE_TOOL = "WriteObsidianNoteTool"
        const val READ_SKILL_TOOL = "ReadSkillTool"
        const val READ_SKILL_REFERENCE_TOOL = "ReadSkillReferenceTool"
        const val CREATE_SKILL_TOOL = "CreateSkillTool"
        const val RENAME_SKILL_TOOL = "RenameSkillTool"
        const val WRITE_SKILL_REFERENCE_TOOL = "WriteSkillReferenceTool"
        const val DELETE_SKILL_REFERENCE_TOOL = "DeleteSkillReferenceTool"
        const val MOVE_SKILL_REFERENCE_TOOL = "MoveSkillReferenceTool"
    }
}

private object EmptyLocalKnowledgeSearchClient : LocalKnowledgeSearchClient {
    override suspend fun search(request: LocalKnowledgeSearchRequest): Result<List<LocalKnowledgeSearchResult>> {
        return Result.success(emptyList())
    }
}
