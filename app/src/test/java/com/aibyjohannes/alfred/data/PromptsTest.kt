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
            Prompts.SYSTEM_PROMPT.contains("web_search")
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
}
