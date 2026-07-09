package com.aibyjohannes.alfred.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class GrokSearchClientTest {
    @Test
    fun `default model is Grok 4_3`() {
        assertEquals("x-ai/grok-4.3", GrokSearchClient.DEFAULT_MODEL)
    }

    @Test
    fun `search params include OpenRouter native web search server tool`() {
        val params = GrokSearchClient(apiKey = "test").buildSearchParams("latest Kotlin news")

        val tools = params._additionalBodyProperties()["tools"]
            ?.convert(List::class.java) as List<*>
        val tool = tools.first() as Map<*, *>
        val parameters = tool["parameters"] as Map<*, *>

        assertEquals("openrouter:web_search", tool["type"])
        assertEquals("native", parameters["engine"])
    }
}
