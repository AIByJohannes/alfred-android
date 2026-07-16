package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.core.SystemPrompts
import com.aibyjohannes.alfred.core.engine.ChatEngine
import com.aibyjohannes.alfred.core.engine.OpenRouterChatEngine
import com.aibyjohannes.alfred.core.model.ChatStreamEvent
import com.aibyjohannes.alfred.core.search.GrokSearchClient
import com.aibyjohannes.alfred.core.search.LocalKnowledgeSearchClient
import com.aibyjohannes.alfred.core.search.ObsidianClient
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.model.CoreChatMessageKind
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient
import com.aibyjohannes.alfred.core.search.WebSearchClient
import com.aibyjohannes.alfred.core.reminders.ReminderClient
import com.aibyjohannes.alfred.core.skills.SkillClient
import com.aibyjohannes.alfred.core.ticktick.TickTickClient
import com.aibyjohannes.alfred.core.ticktick.TickTickCredentials
import com.aibyjohannes.alfred.core.ticktick.TickTickCredentialsProvider
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.core.audio.OpenRouterAudioClient
import com.aibyjohannes.alfred.core.audio.OpenRouterTtsClient
import com.aibyjohannes.alfred.core.image.OpenRouterImageClient
import com.aibyjohannes.alfred.core.github.GitHubCredentialsProvider
import com.aibyjohannes.alfred.core.github.GitHubMcpClient
import com.aibyjohannes.alfred.core.notion.NotionCredentials
import com.aibyjohannes.alfred.core.notion.NotionCredentialsProvider
import com.aibyjohannes.alfred.core.notion.NotionMcpClient
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

internal data class ChatEngineRequest(
    val apiKey: String,
    val model: String,
    val sysInfo: String?,
    val maxPasses: Int
)

internal class ChatRepositoryDependencies(
    val audioClientFactory: (String, String) -> OpenRouterAudioClient = { apiKey, model ->
        OpenRouterAudioClient(apiKey, model)
    },
    val ttsClientFactory: (String, String, String) -> OpenRouterTtsClient = { apiKey, model, voice ->
        OpenRouterTtsClient(apiKey, model, voice)
    },
    val chatEngineFactory: ((ChatEngineRequest) -> ChatEngine)? = null,
    val localChatEngineFactory: ((modelPath: String, systemPrompt: String) -> ChatEngine)? = null,
    val localModelPathProvider: (() -> String?)? = null
)

class ChatRepository private constructor(
    private val apiKeyStore: ApiKeyStore,
    private val localKnowledgeSearchClient: LocalKnowledgeSearchClient? = null,
    private val obsidianClient: ObsidianClient? = null,
    private val obsidianClientProvider: (() -> ObsidianClient?)? = null,
    private val skillClient: SkillClient? = null,
    private val reminderClient: ReminderClient? = null,
    private val generatedImageDirectory: File? = null,
    private val dependencies: ChatRepositoryDependencies
) {
    constructor(
        apiKeyStore: ApiKeyStore,
        localKnowledgeSearchClient: LocalKnowledgeSearchClient? = null,
        obsidianClient: ObsidianClient? = null,
        obsidianClientProvider: (() -> ObsidianClient?)? = null,
        skillClient: SkillClient? = null,
        reminderClient: ReminderClient? = null,
        generatedImageDirectory: File? = null,
        localChatEngineFactory: ((modelPath: String, systemPrompt: String) -> ChatEngine)? = null,
        localModelPathProvider: (() -> String?)? = null
    ) : this(
        apiKeyStore,
        localKnowledgeSearchClient,
        obsidianClient,
        obsidianClientProvider,
        skillClient,
        reminderClient,
        generatedImageDirectory,
        ChatRepositoryDependencies(
            localChatEngineFactory = localChatEngineFactory,
            localModelPathProvider = localModelPathProvider
        )
    )

    internal constructor(
        apiKeyStore: ApiKeyStore,
        dependencies: ChatRepositoryDependencies
    ) : this(apiKeyStore, null, null, null, null, null, null, dependencies)

    suspend fun transcribeAudio(audioFile: java.io.File): Result<String> {
        val apiKey = loadApiKey()
            ?: return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
        val sttModel = apiKeyStore.loadSttModel()

        return try {
            dependencies.audioClientFactory(apiKey, sttModel).use { client ->
                client.transcribe(audioFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun synthesizeSpeech(text: String, outputFile: File): Result<File> {
        val apiKey = loadApiKey()
            ?: return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
        val ttsModel = apiKeyStore.loadTtsModel()
        val ttsVoice = apiKeyStore.loadTtsVoice()

        return try {
            dependencies.ttsClientFactory(apiKey, ttsModel, ttsVoice).use { client ->
                client.synthesize(text, outputFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        sysInfo: String? = null
    ): Result<String> {
        val apiKey = loadChatApiKey()
            ?: return Result.failure(missingChatConfigurationError())

        val engine = createEngine(apiKey, sysInfo)

        return engine.sendMessage(
            userMessage = userMessage,
            conversationHistory = conversationHistory.toCoreMessages()
        ).map { it.content }
    }

    fun streamMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        sysInfo: String? = null,
        maxPasses: Int? = null,
        sessionId: String? = null
    ): Flow<ChatStreamEvent> {
        val apiKey = loadChatApiKey()
            ?: throw missingChatConfigurationError()
        return createEngine(apiKey, sysInfo, maxPasses, sessionId).streamMessage(
            userMessage = userMessage,
            conversationHistory = conversationHistory.toCoreMessages()
        )
    }

    private fun List<ChatMessage>.toCoreMessages(): List<CoreChatMessage> = map {
        CoreChatMessage(
            role = it.role,
            content = it.content,
            kind = when (it.kind) {
                ChatMessage.KIND_REASONING -> CoreChatMessageKind.REASONING
                ChatMessage.KIND_TOOL_CALL -> CoreChatMessageKind.TOOL_CALL
                ChatMessage.KIND_TOOL_RESULT -> CoreChatMessageKind.TOOL_RESULT
                else -> CoreChatMessageKind.MESSAGE
            },
            turnId = it.turnId,
            toolCallId = it.toolCallId,
            toolName = it.toolName,
            toolArgumentsJson = it.toolArgumentsJson,
            isError = it.isError,
            reasoningText = it.reasoningText,
            reasoningSummary = it.reasoningSummary,
            encryptedReasoning = it.encryptedReasoning,
            includeInPrompt = it.includeInPrompt,
            searchable = it.searchable
        )
    }

    private fun createEngine(
        apiKey: String,
        sysInfo: String? = null,
        maxPasses: Int? = null,
        sessionId: String? = null
    ): ChatEngine {
        val model = apiKeyStore.loadModel()
        val resolvedMaxPasses = maxPasses ?: 10
        if (model == LocalGemmaModelStore.LOCAL_MODEL_ID) {
            val modelPath = dependencies.localModelPathProvider?.invoke()
                ?: throw IllegalStateException("Local Gemma 3n is selected, but no model is installed. Import a .litertlm model in Settings.")
            return dependencies.localChatEngineFactory?.invoke(
                modelPath,
                SystemPrompts.buildSystemPrompt(sysInfo)
            ) ?: throw IllegalStateException("Local Gemma 3n inference is unavailable in this build.")
        }
        dependencies.chatEngineFactory?.let { factory ->
            return factory(ChatEngineRequest(apiKey, model, sysInfo, resolvedMaxPasses))
        }
        val tickTickProvider = object : TickTickCredentialsProvider {
            override fun getCredentials(): TickTickCredentials? {
                val clientId = apiKeyStore.loadTickTickClientId() ?: return null
                val clientSecret = apiKeyStore.loadTickTickClientSecret() ?: return null
                val accessToken = apiKeyStore.loadTickTickAccessToken() ?: return null
                val refreshToken = apiKeyStore.loadTickTickRefreshToken()
                return TickTickCredentials(clientId, clientSecret, accessToken, refreshToken)
            }

            override fun onCredentialsRefreshed(credentials: TickTickCredentials) {
                apiKeyStore.saveTickTickAccessToken(credentials.accessToken)
                credentials.refreshToken?.let { apiKeyStore.saveTickTickRefreshToken(it) }
            }
        }

        val tickTickClient = if (apiKeyStore.loadTickTickClientId() != null) {
            TickTickClient(tickTickProvider)
        } else {
            null
        }

        val notionProvider = object : NotionCredentialsProvider {
            override fun getCredentials(): NotionCredentials? = apiKeyStore.loadNotionCredentials()

            override fun onCredentialsRefreshed(credentials: NotionCredentials) {
                apiKeyStore.saveNotionCredentials(credentials)
            }
        }
        val notionMcpClient = if (apiKeyStore.hasNotionAuth()) {
            NotionMcpClient(notionProvider)
        } else {
            null
        }
        val githubMcpClient = if (apiKeyStore.hasGitHubAuth()) {
            GitHubMcpClient(GitHubCredentialsProvider(apiKeyStore::loadGitHubAccessToken))
        } else {
            null
        }

        return OpenRouterChatEngine(
            apiKey = apiKey,
            model = model,
            prompt = SystemPrompts.buildSystemPrompt(sysInfo),
            webSearchClient = createWebSearchClient(apiKey),
            localKnowledgeSearchClient = localKnowledgeSearchClient,
            tickTickClient = tickTickClient,
            notionMcpClient = notionMcpClient,
            githubMcpClient = githubMcpClient,
            imageGenerator = generatedImageDirectory?.let { directory ->
                { imagePrompt -> generateImageWidget(apiKey, imagePrompt, directory) }
            },
            obsidianClient = resolveObsidianClient(),
            skillClient = resolveSkillClient(),
            reminderClient = reminderClient,
            maxAgentPasses = resolvedMaxPasses,
            efficiencyModeEnabled = apiKeyStore.isEfficiencyModeEnabled(),
            privacyModeEnabled = apiKeyStore.isPrivacyModeEnabled(),
            toolsEnabled = apiKeyStore.areToolsEnabled(),
            sessionId = sessionId
        )
    }

    private fun loadApiKey(): String? = apiKeyStore.loadOpenRouterKey()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun loadChatApiKey(): String? = when {
        apiKeyStore.loadModel() == LocalGemmaModelStore.LOCAL_MODEL_ID -> LOCAL_CHAT_SENTINEL_KEY
        else -> loadApiKey()
    }

    private fun missingChatConfigurationError(): IllegalStateException = IllegalStateException(
        "API key not configured. Please add your OpenRouter API key in Settings."
    )

    /** Returns the currently active ObsidianClient, preferring the dynamic provider over the fixed one. */
    fun resolveObsidianClient(): ObsidianClient? =
        obsidianClientProvider?.invoke() ?: obsidianClient

    fun resolveSkillClient(): SkillClient? = skillClient

    private fun createWebSearchClient(apiKey: String): WebSearchClient {
        return when (apiKeyStore.loadSearchTool()) {
            SEARCH_TOOL_GROK -> GrokSearchClient(apiKey = apiKey, model = GROK_SEARCH_MODEL)
            else -> PerplexitySearchClient(apiKey = apiKey, model = PERPLEXITY_MODEL)
        }
    }

    suspend fun generateImage(prompt: String, outputFile: File): Result<File> {
        val apiKey = loadApiKey()
            ?: return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
        return try {
            OpenRouterImageClient(apiKey).use { client ->
                client.generate(prompt).map { image ->
                    outputFile.parentFile?.mkdirs()
                    outputFile.writeBytes(image.bytes)
                    outputFile
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun generateImageWidget(apiKey: String, prompt: String, directory: File): Result<String> {
        return try {
            OpenRouterImageClient(apiKey).use { client ->
                client.generate(prompt).map { image ->
                    directory.mkdirs()
                    val extension = when (image.mediaType.lowercase()) {
                        "image/jpeg" -> "jpg"
                        "image/webp" -> "webp"
                        else -> "png"
                    }
                    val outputFile = directory.resolve("generated-${UUID.randomUUID()}.$extension")
                    outputFile.writeBytes(image.bytes)
                    val widgetJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        mapOf(
                            "type" to "image",
                            "path" to outputFile.absolutePath,
                            "alt" to prompt.take(240)
                        )
                    )
                    "```alfred-widget\n$widgetJson\n```"
                }
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    // Retained for test compatibility and tool schema intent.
    @JsonClassDescription("Search the web for current information.")
    class WebSearchTool {
        @JsonPropertyDescription("The search query to look up on the web")
        var query: String? = null
    }

    @JsonClassDescription("Search previous local sessions and memories.")
    class SearchLocalKnowledgeTool {
        @JsonPropertyDescription("The search query to look up in local sessions and memories")
        var query: String? = null

        @JsonPropertyDescription("Maximum number of results to return. Defaults to 5 and is capped at 10.")
        var limit: Int? = null

        @JsonPropertyDescription("Which local source to search: all, sessions, or memories")
        var source: String? = null
    }

    @JsonClassDescription("Create a detailed execution plan for a complex task with a stronger reasoning model.")
    class CreatePlanTool {
        @JsonPropertyDescription("The specific complex task, problem, or objective that needs planning, reasoning, or direction")
        var task_details: String? = null

        @JsonPropertyDescription("Additional background information or constraint details to help the planning model")
        var context: String? = null
    }

    companion object {
        const val DEFAULT_MODEL = OpenRouterChatEngine.DEFAULT_MODEL
        const val PERPLEXITY_MODEL = PerplexitySearchClient.DEFAULT_MODEL
        const val DEFAULT_TTS_MODEL = OpenRouterTtsClient.DEFAULT_MODEL
        const val GROK_SEARCH_MODEL = GrokSearchClient.DEFAULT_MODEL
        const val SEARCH_TOOL_PERPLEXITY = "perplexity"
        const val SEARCH_TOOL_GROK = "grok"
        private const val LOCAL_CHAT_SENTINEL_KEY = "local"
    }

    @Deprecated("Use core engine package directly for new integrations.")
    class PerplexitySubAgent(private val apiKeyStore: ApiKeyStore) {
        suspend fun webSearch(query: String): Result<String> {
            val apiKey = apiKeyStore.loadOpenRouterKey()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.failure(Exception("API key not configured."))
            return PerplexitySearchClient(apiKey = apiKey, model = PERPLEXITY_MODEL).search(query)
        }
    }
}
