package com.aibyjohannes.alfred.ui.home

import com.fasterxml.jackson.databind.ObjectMapper

data class TracePayloadPresentation(
    val original: String,
    val formatted: String,
    val isStructuredJson: Boolean,
    val canCollapse: Boolean
) {
    fun displayText(expanded: Boolean): String {
        if (expanded || !canCollapse) return formatted
        return formatted.take(COLLAPSED_CHARACTER_LIMIT).trimEnd() + "\n…"
    }

    private companion object {
        const val COLLAPSED_CHARACTER_LIMIT = 1_200
    }
}

object TracePayloadFormatter {
    private val mapper = ObjectMapper()

    fun format(raw: String): TracePayloadPresentation {
        val normalized = raw.ifBlank { raw }
        val parsed = runCatching { mapper.readTree(raw) }.getOrNull()
        val structured = parsed != null && (parsed.isObject || parsed.isArray)
        val formatted = if (structured) {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed)
        } else {
            normalized
        }
        return TracePayloadPresentation(
            original = raw,
            formatted = formatted,
            isStructuredJson = structured,
            canCollapse = formatted.length > 1_200
        )
    }
}
