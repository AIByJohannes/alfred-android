package com.aibyjohannes.alfred.core.openrouter

/** Public, non-secret metadata used to attribute Alfred's OpenRouter traffic. */
object OpenRouterAttribution {
    const val APP_URL = "https://github.com/AIByJohannes/alfred-android"
    const val APP_TITLE = "Alfred Android"
    const val APP_CATEGORIES = "mobile-agent"

    val headers: Map<String, String> = mapOf(
        "HTTP-Referer" to APP_URL,
        "X-OpenRouter-Title" to APP_TITLE,
        "X-OpenRouter-Categories" to APP_CATEGORIES
    )
}
