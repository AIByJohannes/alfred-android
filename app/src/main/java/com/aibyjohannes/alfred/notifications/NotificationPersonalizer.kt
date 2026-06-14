package com.aibyjohannes.alfred.notifications

import com.aibyjohannes.alfred.data.api.ChatMessage
import com.aibyjohannes.alfred.data.local.ConversationStore

class NotificationPersonalizer(
    private val conversationStore: ConversationStore
) {
    suspend fun buildPrompt(kind: NotificationKind): String {
        val topic = findRecentSafeTopic()
        return when {
            topic != null && kind == NotificationKind.INACTIVITY ->
                "Want to continue your $topic thread with Alfred?"
            topic != null ->
                "Want to pick up your $topic conversation today?"
            kind == NotificationKind.INACTIVITY ->
                "Alfred is ready when you want to continue."
            else ->
                GENERIC_PROMPTS.random()
        }
    }

    private suspend fun findRecentSafeTopic(): String? {
        val recentConversations = conversationStore.listConversations()
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_RECENT_CONVERSATIONS)

        for (conversation in recentConversations) {
            val title = conversation.title?.let(::sanitizeTopic)
            if (title != null) {
                return title
            }

            val recentUserMessage = conversationStore.loadMessages(conversation.id)
                .asReversed()
                .firstOrNull { it.role == ChatMessage.ROLE_USER }
                ?.content
                ?.let(::sanitizeTopic)
            if (recentUserMessage != null) {
                return recentUserMessage
            }
        }

        return null
    }

    private fun sanitizeTopic(raw: String): String? {
        val normalized = raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '.', ',', ':', ';', '-', ' ')

        if (normalized.length < MIN_TOPIC_LENGTH) return null
        if (SECRET_LIKE_PATTERNS.any { it.containsMatchIn(normalized) }) return null

        val withoutUrls = normalized.replace(URL_PATTERN, "").trim()
        if (withoutUrls.length < MIN_TOPIC_LENGTH) return null

        val compact = withoutUrls.take(MAX_TOPIC_LENGTH).trim()
        return compact.trimEnd('.', ',', ':', ';', '-').takeIf { it.length >= MIN_TOPIC_LENGTH }
    }

    companion object {
        private const val MAX_RECENT_CONVERSATIONS = 5
        private const val MIN_TOPIC_LENGTH = 4
        private const val MAX_TOPIC_LENGTH = 48

        private val URL_PATTERN = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
        private val SECRET_LIKE_PATTERNS = listOf(
            Regex("\\b(api[_ -]?key|token|password|secret|credential)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b"),
            Regex("\\b[A-Za-z0-9_-]{32,}\\b")
        )

        private val GENERIC_PROMPTS = listOf(
            "Ask Alfred what to work on next.",
            "Bring Alfred one question for today.",
            "Ready to continue building with Alfred?",
            "Alfred can help you sort out the next step."
        )

        fun genericPrompt(kind: NotificationKind): String {
            return if (kind == NotificationKind.INACTIVITY) {
                "Alfred is ready when you want to continue."
            } else {
                GENERIC_PROMPTS.random()
            }
        }
    }
}

enum class NotificationKind {
    DAILY,
    INACTIVITY
}
