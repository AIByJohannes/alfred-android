package com.aibyjohannes.alfred.data.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ChatMessage data class.
 * Verifies role constants and data class functionality.
 */
class ChatMessageTest {

    @Test
    fun `role constants have correct values`() {
        assertEquals("system", ChatMessage.ROLE_SYSTEM)
        assertEquals("user", ChatMessage.ROLE_USER)
        assertEquals("assistant", ChatMessage.ROLE_ASSISTANT)
    }

    @Test
    fun `ChatMessage stores role and content correctly`() {
        val message = ChatMessage(
            role = ChatMessage.ROLE_USER,
            content = "Hello, Alfred!"
        )
        
        assertEquals("user", message.role)
        assertEquals("Hello, Alfred!", message.content)
    }

    @Test
    fun `ChatMessage equality works correctly`() {
        val message1 = ChatMessage(role = "user", content = "Hello")
        val message2 = ChatMessage(role = "user", content = "Hello")
        val message3 = ChatMessage(role = "assistant", content = "Hello")
        
        assertEquals(message1, message2)
        assertNotEquals(message1, message3)
    }

    @Test
    fun `ChatMessage copy works correctly`() {
        val original = ChatMessage(role = "user", content = "Original")
        val copied = original.copy(content = "Modified")
        
        assertEquals("user", copied.role)
        assertEquals("Modified", copied.content)
        assertEquals("Original", original.content)
    }
}
