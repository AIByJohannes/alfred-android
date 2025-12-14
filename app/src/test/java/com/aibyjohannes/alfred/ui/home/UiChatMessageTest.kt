package com.aibyjohannes.alfred.ui.home

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for UiChatMessage data class.
 * Verifies message creation and default values.
 */
class UiChatMessageTest {

    @Test
    fun `UiChatMessage has correct default values`() {
        val message = UiChatMessage(
            content = "Test message",
            isUser = true
        )
        
        assertEquals("Test message", message.content)
        assertTrue(message.isUser)
        assertFalse(message.isError) // Default should be false
    }

    @Test
    fun `user message is created correctly`() {
        val userMessage = UiChatMessage(
            content = "Hello, Alfred!",
            isUser = true
        )
        
        assertTrue(userMessage.isUser)
        assertFalse(userMessage.isError)
        assertEquals("Hello, Alfred!", userMessage.content)
    }

    @Test
    fun `assistant message is created correctly`() {
        val assistantMessage = UiChatMessage(
            content = "Hello! How can I help you?",
            isUser = false
        )
        
        assertFalse(assistantMessage.isUser)
        assertFalse(assistantMessage.isError)
        assertEquals("Hello! How can I help you?", assistantMessage.content)
    }

    @Test
    fun `error message is created correctly`() {
        val errorMessage = UiChatMessage(
            content = "An error occurred",
            isUser = false,
            isError = true
        )
        
        assertFalse(errorMessage.isUser)
        assertTrue(errorMessage.isError)
        assertEquals("An error occurred", errorMessage.content)
    }

    @Test
    fun `UiChatMessage equality works correctly`() {
        val message1 = UiChatMessage(content = "Hello", isUser = true)
        val message2 = UiChatMessage(content = "Hello", isUser = true)
        val message3 = UiChatMessage(content = "Hello", isUser = false)
        
        assertEquals(message1, message2)
        assertNotEquals(message1, message3)
    }
}
