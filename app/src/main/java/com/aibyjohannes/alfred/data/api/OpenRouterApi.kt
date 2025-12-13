package com.aibyjohannes.alfred.data.api

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenRouterApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatCompletionRequest): ChatCompletionResponse

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1/"

        // Using IBM Granite model
        const val DEFAULT_MODEL = "ibm-granite/granite-4.0-h-micro"
    }
}

