package com.aibyjohannes.alfred.ui.home

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface ChatWidget {
    data class Weather(
        val location: String,
        val temperature: String,
        val condition: String,
        val details: String? = null
    ) : ChatWidget

    data class YouTube(
        val url: String,
        val videoId: String,
        val title: String? = null
    ) : ChatWidget
    data class Image(val path: String, val alt: String? = null) : ChatWidget
}

data class ParsedChatWidgets(val displayContent: String, val widgets: List<ChatWidget>)

object ChatWidgetParser {
    private val mapper = ObjectMapper()
    // Match the fenced payload instead of JSON braces. Android's ICU regex engine rejects an
    // unescaped closing brace that the desktop JVM accepts, which used to crash on the first
    // rendered chat message when this object was initialized.
    private val widgetBlock = Regex(
        "```alfred-widget\\s*(.*?)\\s*```",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val webUrl = Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun parse(content: String): ParsedChatWidgets {
        val widgets = widgetBlock.findAll(content).mapNotNull { match ->
            runCatching {
                val node = mapper.readTree(match.groupValues[1])
                when (node.path("type").asText().lowercase()) {
                    "weather" -> ChatWidget.Weather(
                        location = node.path("location").asText("Current location"),
                        temperature = node.path("temperature").asText("—"),
                        condition = node.path("condition").asText("Weather"),
                        details = node.path("details").asText().takeIf(String::isNotBlank)
                    )
                    "youtube" -> node.path("url").asText().takeIf { it.startsWith("https://") }?.let { url ->
                        extractYouTubeVideoId(url)?.let { videoId ->
                            ChatWidget.YouTube(
                                url = url,
                                videoId = videoId,
                                title = node.path("title").asText().takeIf(String::isNotBlank)
                            )
                        }
                    }
                    "image" -> node.path("path").asText().takeIf(String::isNotBlank)?.let {
                        ChatWidget.Image(it, node.path("alt").asText().takeIf(String::isNotBlank))
                    }
                    else -> null
                }
            }.getOrNull()
        }.toMutableList()

        val displayContent = widgetBlock.replace(content, "").trim()
        val explicitYouTubeUrls = widgets.filterIsInstance<ChatWidget.YouTube>().mapTo(mutableSetOf()) { it.url }
        webUrl.findAll(displayContent)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ']', '}') }
            .distinct()
            .forEach { url ->
                val videoId = extractYouTubeVideoId(url) ?: return@forEach
                if (url !in explicitYouTubeUrls) widgets += ChatWidget.YouTube(url, videoId)
            }
        return ParsedChatWidgets(displayContent, widgets)
    }

    internal fun extractYouTubeVideoId(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val candidate = when (host) {
            "youtu.be" -> uri.path.orEmpty().trim('/').substringBefore('/')
            "youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty()
                    .split('&')
                    .mapNotNull { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        if (parts.firstOrNull() == "v") {
                            URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
                        } else {
                            null
                        }
                    }
                    .firstOrNull()
                uri.path.orEmpty().startsWith("/shorts/") -> uri.path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        candidate?.takeIf { videoIdPattern.matches(it) }
    }.getOrNull()
}
