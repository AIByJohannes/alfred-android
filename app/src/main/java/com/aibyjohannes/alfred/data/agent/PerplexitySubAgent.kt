package com.aibyjohannes.alfred.data.agent

import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.api.ChatCompletionRequestWithTools
import com.aibyjohannes.alfred.data.api.ChatMessageWithTools
import com.aibyjohannes.alfred.data.api.ChatCompletionResponseWithTools
import com.aibyjohannes.alfred.data.api.OpenRouterApi
import com.aibyjohannes.alfred.data.api.OpenRouterHeadersInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Perplexity Sub-Agent for web search capabilities.
 * 
 * This agent uses Perplexity Sonar via OpenRouter to perform web searches
 * and return current information from the internet.
 */
class PerplexitySubAgent(private val apiKeyStore: ApiKeyStore) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(OpenRouterHeadersInterceptor { apiKeyStore.loadOpenRouterKey() })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(OpenRouterApi.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(PerplexityApi::class.java)

    /**
     * Perform a web search using Perplexity Sonar
     * 
     * @param query The search query to look up
     * @return The search results as a formatted string, or an error message
     */
    suspend fun webSearch(query: String): Result<String> {
        return try {
            val messages = listOf(
                ChatMessageWithTools(
                    role = ChatMessageWithTools.ROLE_SYSTEM,
                    content = "You are a helpful web search assistant. Provide concise, accurate, and up-to-date information based on your web search capabilities. Include relevant sources when available."
                ),
                ChatMessageWithTools(
                    role = ChatMessageWithTools.ROLE_USER,
                    content = query
                )
            )

            val request = ChatCompletionRequestWithTools(
                model = OpenRouterApi.PERPLEXITY_MODEL,
                messages = messages,
                stream = false
            )

            val response = api.chatCompletions(request)

            val content = response.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("No response from Perplexity"))

            Result.success(content)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(Exception("Web search failed: ${e.message}"))
        }
    }

    private interface PerplexityApi {
        @POST("chat/completions")
        suspend fun chatCompletions(@Body request: ChatCompletionRequestWithTools): ChatCompletionResponseWithTools
    }
}
