package com.aibyjohannes.alfred.data.api

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenRouterApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatCompletionRequest): ChatCompletionResponse

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1/"

        // Using a free-tier model - Llama 3.2 3B Instruct (free)
        const val DEFAULT_MODEL = "meta-llama/llama-3.2-3b-instruct:free"
    }
}

