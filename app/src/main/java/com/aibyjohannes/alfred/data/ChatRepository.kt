package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.data.agent.PerplexitySubAgent
import com.aibyjohannes.alfred.data.api.ChatCompletionRequestWithTools
import com.aibyjohannes.alfred.data.api.ChatCompletionResponseWithTools
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.api.ChatMessageWithTools
import com.aibyjohannes.alfred.data.api.OpenRouterApi
import com.aibyjohannes.alfred.data.api.OpenRouterHeadersInterceptor
import com.aibyjohannes.alfred.data.api.Tools
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

class ChatRepository(private val apiKeyStore: ApiKeyStore) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(OpenRouterHeadersInterceptor { apiKeyStore.loadOpenRouterKey() })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(OpenRouterApi.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val toolApi = retrofit.create(ToolEnabledApi::class.java)
    private val perplexitySubAgent = PerplexitySubAgent(apiKeyStore)

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        return try {
            if (!apiKeyStore.hasApiKey()) {
                return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
            }

            // Build messages with tool support
            val messages = buildList {
                add(ChatMessageWithTools(
                    role = ChatMessageWithTools.ROLE_SYSTEM,
                    content = Prompts.SYSTEM_PROMPT
                ))
                // Add conversation history
                conversationHistory.forEach { msg ->
                    add(ChatMessageWithTools(
                        role = msg.role,
                        content = msg.content
                    ))
                }
                add(ChatMessageWithTools(role = ChatMessageWithTools.ROLE_USER, content = userMessage))
            }

            // Send request with tools
            val request = ChatCompletionRequestWithTools(
                model = OpenRouterApi.DEFAULT_MODEL,
                messages = messages,
                tools = Tools.ALL_TOOLS,
                toolChoice = "auto",
                stream = false
            )

            val response = toolApi.chatCompletionsWithTools(request)
            val assistantMessage = response.choices.firstOrNull()?.message
                ?: return Result.failure(Exception("No response from AI"))

            // Check if the model wants to call a tool
            val toolCalls = assistantMessage.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                // Process tool calls
                val toolResults = mutableListOf<ChatMessageWithTools>()
                
                // Add the assistant's message with tool calls
                toolResults.add(assistantMessage)

                for (toolCall in toolCalls) {
                    val result = when (toolCall.function.name) {
                        "web_search" -> {
                            // Parse the arguments
                            val argsAdapter = moshi.adapter(WebSearchArgs::class.java)
                            val args = argsAdapter.fromJson(toolCall.function.arguments)
                            val query = args?.query ?: "search query"
                            
                            // Call Perplexity sub-agent
                            val searchResult = perplexitySubAgent.webSearch(query)
                            searchResult.getOrElse { "Web search failed: ${it.message}" }
                        }
                        else -> "Unknown tool: ${toolCall.function.name}"
                    }

                    // Add tool result as a message
                    toolResults.add(ChatMessageWithTools(
                        role = ChatMessageWithTools.ROLE_TOOL,
                        content = result,
                        toolCallId = toolCall.id
                    ))
                }

                // Send follow-up request with tool results
                val followUpMessages = messages + toolResults
                val followUpRequest = ChatCompletionRequestWithTools(
                    model = OpenRouterApi.DEFAULT_MODEL,
                    messages = followUpMessages,
                    stream = false
                )

                val finalResponse = toolApi.chatCompletionsWithTools(followUpRequest)
                val finalContent = finalResponse.choices.firstOrNull()?.message?.content
                    ?: return Result.failure(Exception("No final response from AI"))

                Result.success(finalContent)
            } else {
                // No tool calls, return direct response
                val content = assistantMessage.content
                    ?: return Result.failure(Exception("No response content from AI"))
                Result.success(content)
            }
        } catch (e: retrofit2.HttpException) {
            e.printStackTrace()
            val errorBody = e.response()?.errorBody()?.string()
            val errorMessage = try {
                if (errorBody != null) {
                    val adapter = moshi.adapter(com.aibyjohannes.alfred.data.api.OpenRouterError::class.java)
                    val errorResponse = adapter.fromJson(errorBody)
                    errorResponse?.error?.message ?: "Error: ${e.code()} ${e.message()}"
                } else {
                    "Error: ${e.code()} ${e.message()}"
                }
            } catch (parseError: Exception) {
                parseError.printStackTrace()
                "Error: ${e.code()} ${e.message()}"
            }
            Result.failure(Exception(errorMessage))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private interface ToolEnabledApi {
        @POST("chat/completions")
        suspend fun chatCompletionsWithTools(@Body request: ChatCompletionRequestWithTools): ChatCompletionResponseWithTools
    }

    private data class WebSearchArgs(val query: String?)
}
