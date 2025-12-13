package com.aibyjohannes.alfred.data

import com.aibyjohannes.alfred.data.api.ChatCompletionRequest
import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.api.OpenRouterApi
import com.aibyjohannes.alfred.data.api.OpenRouterHeadersInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
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

    private val api = retrofit.create(OpenRouterApi::class.java)

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        return try {
            if (!apiKeyStore.hasApiKey()) {
                return Result.failure(Exception("API key not configured. Please add your OpenRouter API key in Settings."))
            }

            val messages = buildList {
                add(ChatMessage(
                    role = "system",
                    content = "You are A.L.F.R.E.D., a helpful AI assistant. Be concise, friendly, and helpful."
                ))
                addAll(conversationHistory)
                add(ChatMessage(role = "user", content = userMessage))
            }

            val request = ChatCompletionRequest(
                model = OpenRouterApi.DEFAULT_MODEL,
                messages = messages,
                stream = false
            )

            val response = api.chatCompletions(request)

            val assistantMessage = response.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("No response from AI"))

            Result.success(assistantMessage)
        } catch (e: retrofit2.HttpException) {
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
                "Error: ${e.code()} ${e.message()}"
            }
            Result.failure(Exception(errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

