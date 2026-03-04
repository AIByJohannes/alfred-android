package com.aibyjohannes.alfred.data.api

import com.aibyjohannes.alfred.data.ChatRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the WebSearchTool class used with the OpenAI SDK's tool calling.
 */
class ToolModelsTest {

    @Test
    fun `WebSearchTool can be instantiated`() {
        val tool = ChatRepository.WebSearchTool()
        assertNull(tool.query)
    }

    @Test
    fun `WebSearchTool query can be set`() {
        val tool = ChatRepository.WebSearchTool()
        tool.query = "latest AI news"
        assertEquals("latest AI news", tool.query)
    }

    @Test
    fun `DEFAULT_MODEL constant is non-empty`() {
        assertTrue(ChatRepository.DEFAULT_MODEL.isNotBlank())
    }

    @Test
    fun `PERPLEXITY_MODEL constant is non-empty`() {
        assertTrue(ChatRepository.PERPLEXITY_MODEL.isNotBlank())
    }

    @Test
    fun `DEFAULT_MODEL contains valid model name`() {
        // Sanity check: model name should look like a provider/model format
        assertTrue(ChatRepository.DEFAULT_MODEL.contains("/"))
    }
}
