package com.aibyjohannes.alfred.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentObsidianClientRegexTest {
    @Test
    fun `search treats user terms as literal words on Android regex`() {
        val pattern = Regex("\\b${Regex.escape("a.b")}\\b", RegexOption.IGNORE_CASE)
        assertEquals(true, pattern.containsMatchIn("A.B is a literal term"))
        assertEquals(false, pattern.containsMatchIn("A0B is not the same term"))
    }
}
