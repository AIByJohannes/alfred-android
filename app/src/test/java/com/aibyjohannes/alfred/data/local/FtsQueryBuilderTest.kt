package com.aibyjohannes.alfred.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `blank query returns empty string`() {
        assertEquals("", FtsQueryBuilder.build(""))
        assertEquals("", FtsQueryBuilder.build("   "))
    }

    @Test
    fun `punctuation only query returns empty string`() {
        assertEquals("", FtsQueryBuilder.build("!@#  $%^"))
    }

    @Test
    fun `normal words are converted to prefix terms`() {
        assertEquals("android* obsidian*", FtsQueryBuilder.build("android obsidian"))
    }

    @Test
    fun `special characters do not crash and are stripped`() {
        assertEquals("android* obsidian*", FtsQueryBuilder.build("android-obsidian!"))
        assertEquals("query* terms*", FtsQueryBuilder.build("query &^%* terms"))
    }

    @Test
    fun `preserves casing by converting to lowercase`() {
        assertEquals("android* obsidian*", FtsQueryBuilder.build("Android Obsidian"))
    }
}
