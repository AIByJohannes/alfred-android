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
        val widget = parsed.widgets.single() as ChatWidget.YouTube
        assertEquals("dQw4w9WgXcQ", widget.videoId)
        assertFalse(parsed.displayContent.isBlank())
    }

    @Test
    fun `youtube watch short and shorts URLs extract their video IDs`() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share",
            "https://youtu.be/dQw4w9WgXcQ?t=2",
            "https://youtube.com/shorts/dQw4w9WgXcQ"
        )

        urls.forEach { url ->
            assertEquals("dQw4w9WgXcQ", ChatWidgetParser.extractYouTubeVideoId(url))
            assertEquals("dQw4w9WgXcQ", (ChatWidgetParser.parse(url).widgets.single() as ChatWidget.YouTube).videoId)
        }
    }

    @Test
    fun `invalid unsupported links remain ordinary content`() {
        listOf(
            "https://youtube.com/watch?v=short",
            "https://youtube.com/channel/dQw4w9WgXcQ",
            "https://example.com/watch?v=dQw4w9WgXcQ"
        ).forEach { url ->
            val parsed = ChatWidgetParser.parse("Open $url")
            assertTrue(parsed.widgets.isEmpty())
            assertTrue(parsed.displayContent.contains(url))
        }
    }

    @Test
    fun `explicit and detected youtube URLs are deduplicated`() {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        val parsed = ChatWidgetParser.parse(
            """Watch $url
                |```alfred-widget
                |{"type":"youtube","url":"$url","title":"A video"}
                |```""".trimMargin()
        )

        assertEquals(1, parsed.widgets.filterIsInstance<ChatWidget.YouTube>().size)
        assertEquals("A video", (parsed.widgets.single() as ChatWidget.YouTube).title)
    }
}
