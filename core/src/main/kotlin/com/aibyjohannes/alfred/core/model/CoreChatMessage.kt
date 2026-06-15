package com.aibyjohannes.alfred.core.model

data class CoreChatMessage(
    val role: String,
    val content: String,
    val kind: CoreChatMessageKind = CoreChatMessageKind.MESSAGE,
    val turnId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgumentsJson: String? = null,
    val isError: Boolean = false,
    val reasoningText: String? = null,
    val reasoningSummary: String? = null,
    val encryptedReasoning: String? = null,
    val includeInPrompt: Boolean = true,
    val searchable: Boolean = true
)
