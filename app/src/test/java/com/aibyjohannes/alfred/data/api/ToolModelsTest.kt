package com.aibyjohannes.alfred.data.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Tool model classes.
 * Verifies tool definitions are properly configured.
 */
class ToolModelsTest {

    @Test
    fun `WEB_SEARCH tool has correct function name`() {
        assertEquals("web_search", Tools.WEB_SEARCH.function.name)
    }

    @Test
    fun `WEB_SEARCH tool has type function`() {
        assertEquals("function", Tools.WEB_SEARCH.type)
    }

    @Test
    fun `WEB_SEARCH tool has query parameter`() {
        val properties = Tools.WEB_SEARCH.function.parameters.properties
        assertTrue(
            "WEB_SEARCH should have a 'query' parameter",
            properties.containsKey("query")
        )
    }

    @Test
    fun `WEB_SEARCH query parameter is type string`() {
        val queryParam = Tools.WEB_SEARCH.function.parameters.properties["query"]
        assertNotNull(queryParam)
        assertEquals("string", queryParam?.type)
    }

    @Test
    fun `WEB_SEARCH requires query parameter`() {
        val required = Tools.WEB_SEARCH.function.parameters.required
        assertTrue(
            "query should be required",
            required.contains("query")
        )
    }

    @Test
    fun `WEB_SEARCH tool has description`() {
        assertTrue(
            "WEB_SEARCH should have a description",
            Tools.WEB_SEARCH.function.description.isNotBlank()
        )
    }

    @Test
    fun `ALL_TOOLS contains WEB_SEARCH`() {
        assertTrue(
            "ALL_TOOLS should contain WEB_SEARCH",
            Tools.ALL_TOOLS.contains(Tools.WEB_SEARCH)
        )
    }

    @Test
    fun `ALL_TOOLS has expected size`() {
        assertEquals(
            "ALL_TOOLS should have 1 tool",
            1,
            Tools.ALL_TOOLS.size
        )
    }

    @Test
    fun `ChatMessageWithTools role constants are correct`() {
        assertEquals("system", ChatMessageWithTools.ROLE_SYSTEM)
        assertEquals("user", ChatMessageWithTools.ROLE_USER)
        assertEquals("tool", ChatMessageWithTools.ROLE_TOOL)
    }

    @Test
    fun `ChatMessageWithTools can be created with minimal parameters`() {
        val message = ChatMessageWithTools(
            role = ChatMessageWithTools.ROLE_USER,
            content = "Hello"
        )
        
        assertEquals("user", message.role)
        assertEquals("Hello", message.content)
        assertNull(message.toolCalls)
        assertNull(message.toolCallId)
    }

    @Test
    fun `ToolCall can be created correctly`() {
        val toolCall = ToolCall(
            id = "call_123",
            function = FunctionCall(
                name = "web_search",
                arguments = "{\"query\": \"test\"}"
            )
        )
        
        assertEquals("call_123", toolCall.id)
        assertEquals("function", toolCall.type)
        assertEquals("web_search", toolCall.function.name)
    }
}
