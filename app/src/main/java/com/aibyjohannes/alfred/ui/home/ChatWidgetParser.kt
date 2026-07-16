package com.aibyjohannes.alfred.ui.home

import com.fasterxml.jackson.databind.ObjectMapper

sealed interface ChatWidget {
    data class Weather(
        val location: String,
        val temperature: String,
        val condition: String,
        val details: String? = null
    ) : ChatWidget

    data class YouTube(val url: String, val title: String? = null) : ChatWidget
    data class Image(val path: String, val alt: String? = null) : ChatWidget
}

data class ParsedChatWidgets(val displayContent: String, val widgets: List<ChatWidget>)

object ChatWidgetParser {
    private val mapper = ObjectMapper()
    private val widgetBlock = Regex("```alfred-widget\\s*(\\{.*?})\\s*```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val youtubeUrl = Regex("https?://(?:www\\.)?(?:youtube\\.com/watch\\?[^\\s)]*v=|youtu\\.be/)[A-Za-z0-9_-]+[^\\s)]*", RegexOption.IGNORE_CASE)

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
                    "youtube" -> node.path("url").asText().takeIf { it.startsWith("https://") }?.let {
                        ChatWidget.YouTube(it, node.path("title").asText().takeIf(String::isNotBlank))
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
        youtubeUrl.findAll(displayContent).map { it.value }.distinct().forEach { url ->
            if (url !in explicitYouTubeUrls) widgets += ChatWidget.YouTube(url)
        }
        return ParsedChatWidgets(displayContent, widgets)
    }
}
