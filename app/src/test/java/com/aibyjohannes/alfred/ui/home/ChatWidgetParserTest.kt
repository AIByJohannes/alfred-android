package com.aibyjohannes.alfred.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWidgetParserTest {
    @Test
    fun `generated image widget becomes structured widget and is removed from markdown`() {
        val parsed = ChatWidgetParser.parse(
            """Done.
                |```alfred-widget
                |{"type":"image","path":"/tmp/generated.png","alt":"A tree"}
                |```""".trimMargin()
        )

        assertEquals("Done.", parsed.displayContent)
        assertEquals(ChatWidget.Image("/tmp/generated.png", "A tree"), parsed.widgets.single())
    }

    @Test
    fun `weather widget block becomes structured widget and is removed from markdown`() {
        val parsed = ChatWidgetParser.parse(
            """Forecast follows.
                |```alfred-widget
                |{"type":"weather","location":"Berlin","temperature":"21°C","condition":"Sunny","details":"Feels like {22°C}"}
                |```""".trimMargin()
        )
        assertEquals("Forecast follows.", parsed.displayContent)
        val weather = parsed.widgets.single() as ChatWidget.Weather
        assertEquals("Berlin", weather.location)
        assertEquals("21°C", weather.temperature)
        assertEquals("Feels like {22°C}", weather.details)
    }

    @Test
    fun `plain first message parses without widgets`() {
        val parsed = ChatWidgetParser.parse("Hello from Luna")

        assertEquals("Hello from Luna", parsed.displayContent)
        assertTrue(parsed.widgets.isEmpty())
    }

    @Test
    fun `youtube links automatically get a clickable widget`() {
        val parsed = ChatWidgetParser.parse("Watch https://youtu.be/dQw4w9WgXcQ")
        assertTrue(parsed.widgets.single() is ChatWidget.YouTube)
        assertFalse(parsed.displayContent.isBlank())
    }
}
