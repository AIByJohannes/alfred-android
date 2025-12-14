package com.aibyjohannes.alfred.data.api

import com.squareup.moshi.Json

/**
 * Defines a tool that can be called by the model
 */
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String> = emptyList()
)

data class PropertyDefinition(
    val type: String,
    val description: String
)

/**
 * Represents a tool call made by the model
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON string of arguments
)

/**
 * Message with tool call support
 */
data class ChatMessageWithTools(
    val role: String,
    val content: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<ToolCall>? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_TOOL = "tool"
    }
}

/**
 * Extended request with tool support
 */
data class ChatCompletionRequestWithTools(
    val model: String,
    val messages: List<ChatMessageWithTools>,
    val tools: List<ToolDefinition>? = null,
    @Json(name = "tool_choice") val toolChoice: String? = null,
    val stream: Boolean = false
)

/**
 * Extended response with tool call support
 */
data class ChatCompletionResponseWithTools(
    val id: String?,
    val choices: List<ChoiceWithTools>
) {
    data class ChoiceWithTools(
        val index: Int,
        val message: ChatMessageWithTools,
        @Json(name = "finish_reason") val finishReason: String?
    )
}

/**
 * Tool definitions for Alfred
 */
object Tools {
    val WEB_SEARCH = ToolDefinition(
        function = FunctionDefinition(
            name = "web_search",
            description = "Search the web for current information. Use this when you need up-to-date information about recent events, news, current facts, or anything that requires internet access.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "query" to PropertyDefinition(
                        type = "string",
                        description = "The search query to look up on the web"
                    )
                ),
                required = listOf("query")
            )
        )
    )

    val ALL_TOOLS = listOf(WEB_SEARCH)
}
