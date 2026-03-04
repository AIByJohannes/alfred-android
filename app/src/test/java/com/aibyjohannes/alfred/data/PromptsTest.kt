package com.aibyjohannes.alfred.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Prompts object.
 * Verifies system prompt contains required information.
 */
class PromptsTest {

    @Test
    fun `system prompt contains Alfred identity`() {
        assertTrue(
            "System prompt should contain A.L.F.R.E.D.",
            Prompts.SYSTEM_PROMPT.contains("A.L.F.R.E.D.")
        )
    }

    @Test
    fun `system prompt mentions Johannes as creator`() {
        assertTrue(
            "System prompt should mention Johannes",
            Prompts.SYSTEM_PROMPT.contains("Johannes")
        )
    }

    @Test
    fun `system prompt describes web search tool`() {
        assertTrue(
            "System prompt should describe web_search tool",
            Prompts.SYSTEM_PROMPT.contains("WebSearchTool")
        )
    }

    @Test
    fun `system prompt is not empty`() {
        assertTrue(
            "System prompt should not be empty",
            Prompts.SYSTEM_PROMPT.isNotBlank()
        )
    }

    @Test
    fun `system prompt describes helpful AI assistant behavior`() {
        assertTrue(
            "System prompt should describe helpful behavior",
            Prompts.SYSTEM_PROMPT.lowercase().contains("helpful")
        )
    }

    @Test
    fun `system prompt tells model not to assume user name`() {
        assertTrue(
            "System prompt should explicitly prevent assuming a user name",
            Prompts.SYSTEM_PROMPT.lowercase().contains("do not assume the user's name")
        )
    }

    @Test
    fun `system prompt forbids claiming missing web access when tool exists`() {
        assertTrue(
            "System prompt should forbid false no-internet or no-web-search claims",
            Prompts.SYSTEM_PROMPT.lowercase().contains("never claim you cannot access the internet or cannot search the web when this tool is available")
        )
    }
}
