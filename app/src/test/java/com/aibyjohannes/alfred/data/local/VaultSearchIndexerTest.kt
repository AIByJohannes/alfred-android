package com.aibyjohannes.alfred.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultSearchIndexerTest {

    @Test
    fun `extractTitle extracts first H1 heading`() {
        val content = """
            # Document Title
            Some body content here.
            ## Heading 2
        """.trimIndent()
        assertEquals("Document Title", VaultSearchIndexer.extractTitle(content, "Work/Spec.md"))
    }

    @Test
    fun `extractTitle extracts H1 even with leading spaces or text before it`() {
        val content = """
            ---
            draft: true
            ---
            # Custom Title
            Content
        """.trimIndent()
        assertEquals("Custom Title", VaultSearchIndexer.extractTitle(content, "Work/Spec.md"))
    }

    @Test
    fun `extractTitle falls back to filename without md extension if no H1 exists`() {
        val content = """
            Some body content here without any H1.
            ## Heading 2
        """.trimIndent()
        assertEquals("Spec", VaultSearchIndexer.extractTitle(content, "Work/Spec.md"))
    }

    @Test
    fun `extractTitle falls back to filename if H1 is empty`() {
        val content = """
            # 
            Content
        """.trimIndent()
        assertEquals("Spec", VaultSearchIndexer.extractTitle(content, "Work/Spec.md"))
    }
}
