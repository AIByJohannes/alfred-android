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
    fun `SearchLocalKnowledgeTool can be instantiated`() {
        val tool = ChatRepository.SearchLocalKnowledgeTool()
        assertNull(tool.query)
        assertNull(tool.limit)
        assertNull(tool.source)
    }

    @Test
    fun `SearchLocalKnowledgeTool fields can be set`() {
        val tool = ChatRepository.SearchLocalKnowledgeTool()
        tool.query = "what did I say about Kotlin"
        tool.limit = 3
        tool.source = "sessions"

        assertEquals("what did I say about Kotlin", tool.query)
        assertEquals(3, tool.limit)
        assertEquals("sessions", tool.source)
    }

    @Test
    fun `AskSmartModelTool can be instantiated`() {
        val tool = ChatRepository.AskSmartModelTool()
        assertNull(tool.task_details)
        assertNull(tool.context)
    }

    @Test
    fun `AskSmartModelTool fields can be set`() {
        val tool = ChatRepository.AskSmartModelTool()
        tool.task_details = "Plan my study schedule"
        tool.context = "Target 2 hours daily"

        assertEquals("Plan my study schedule", tool.task_details)
        assertEquals("Target 2 hours daily", tool.context)
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
    fun `search tool constants are non-empty`() {
        assertTrue(ChatRepository.SEARCH_TOOL_PERPLEXITY.isNotBlank())
        assertTrue(ChatRepository.SEARCH_TOOL_GROK.isNotBlank())
    }

    @Test
    fun `DEFAULT_MODEL contains valid model name`() {
        // Sanity check: model name should look like a provider/model format
        assertTrue(ChatRepository.DEFAULT_MODEL.contains("/"))
    }
}
