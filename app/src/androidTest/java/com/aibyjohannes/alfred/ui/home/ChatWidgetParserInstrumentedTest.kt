package com.aibyjohannes.alfred.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWidgetParserInstrumentedTest {
    @Test
    fun parserInitializesOnAndroidWhenFirstMessageIsRendered() {
        val parsed = ChatWidgetParser.parse("Hello from Luna")

        assertEquals("Hello from Luna", parsed.displayContent)
        assertTrue(parsed.widgets.isEmpty())
    }
}
