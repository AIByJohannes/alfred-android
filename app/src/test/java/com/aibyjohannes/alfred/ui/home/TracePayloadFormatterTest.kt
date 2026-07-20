package com.aibyjohannes.alfred.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TracePayloadFormatterTest {
    @Test
    fun `nested JSON is pretty printed without changing the original payload`() {
        val raw = """{"query":"Kotlin","filters":{"tags":["android","日本語"],"empty":null}}"""

        val result = TracePayloadFormatter.format(raw)

        assertTrue(result.isStructuredJson)
        assertTrue(result.formatted.contains("\n"))
        assertTrue(result.formatted.contains("  \"filters\""))
        assertTrue(result.formatted.contains("日本語"))
        assertEquals(raw, result.original)
    }

    @Test
    fun `arrays and escaped strings format safely`() {
        val result = TracePayloadFormatter.format("""[1,true,"line\\nnext",{},[]]""")

        assertTrue(result.isStructuredJson)
        assertTrue(result.formatted.startsWith("["))
        assertTrue(result.formatted.contains("line\\\\nnext"))
    }

    @Test
    fun `partial invalid and primitive payloads remain readable raw text`() {
        listOf("{\"query\":", "not json", "42", "null").forEach { raw ->
            val result = TracePayloadFormatter.format(raw)
            assertFalse(result.isStructuredJson)
            assertEquals(raw, result.formatted)
        }
    }

    @Test
    fun `long payload collapses while retaining complete original`() {
        val raw = """{"value":"${"x".repeat(2_000)}"}"""
        val result = TracePayloadFormatter.format(raw)

        assertTrue(result.canCollapse)
        assertTrue(result.displayText(expanded = false).endsWith("…"))
        assertEquals(result.formatted, result.displayText(expanded = true))
        assertEquals(raw, result.original)
    }
}
