package com.aibyjohannes.alfred.data.api

import okhttp3.Interceptor
import okhttp3.Response

class OpenRouterHeadersInterceptor(
    private val apiKeyProvider: () -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            // Proceed without auth header - will fail on API side
            return chain.proceed(originalRequest)
        }

        val newRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $apiKey")
            .header("Referer", "https://github.com/AIByJohannes/alfred-android")
            .header("X-Title", "Alfred Android")
            .build()

        return chain.proceed(newRequest)
    }
}

