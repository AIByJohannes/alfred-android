package com.aibyjohannes.alfred.data.api

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenRouterApi {

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1/"

        // Using Gemini Flash 2.5 Lite as primary agent (supports tool calling)
        const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite"

        // Perplexity Sonar for web search sub-agent
        const val PERPLEXITY_MODEL = "perplexity/sonar"
    }
}

