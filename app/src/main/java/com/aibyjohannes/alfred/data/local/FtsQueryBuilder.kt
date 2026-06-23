package com.aibyjohannes.alfred.data.local

import java.util.Locale

internal object FtsQueryBuilder {
    /**
     * Converts a raw user query into a safe FTS4 query.
     * Sanitizes inputs to avoid syntax errors and appends wildcards for prefix matching.
     */
    fun build(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return ""

        // Extract alphanumeric tokens
        val terms = trimmed.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

        if (terms.isEmpty()) return ""

        // Return FTS prefix matching representation: "term1* term2*"
        // FTS4 treats space as implicit AND, and suffix '*' enables prefix search on terms
        return terms.joinToString(" ") { "$it*" }
    }
}
