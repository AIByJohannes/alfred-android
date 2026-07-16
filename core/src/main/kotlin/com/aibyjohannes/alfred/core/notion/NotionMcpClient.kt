package com.aibyjohannes.alfred.core.notion

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class NotionMcpClient(
    private val credentialsProvider: NotionCredentialsProvider,
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val objectMapper: ObjectMapper = ObjectMapper()
) : AutoCloseable {
    private val initializationMutex = Mutex()
    private var initialized = false
    private var sessionId: String? = null
    private val exposedToRemoteToolNames = mutableMapOf<String, String>()

    suspend fun getMcpTools(): List<ToolDescriptor> {
        val root = runCatching { requestMcp("tools/list", objectMapper.createObjectNode()) }.getOrNull()
            ?: return emptyList()
        val tools = root.path("result").path("tools")
        if (!tools.isArray) return emptyList()
        return tools.mapNotNull { tool ->
            val remoteName = tool.path("name").asText().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val exposedName = exposeName(remoteName)
            exposedToRemoteToolNames[exposedName] = remoteName
            val schema = tool.path("inputSchema")
            val required = schema.path("required").mapTo(mutableSetOf()) { it.asText() }
            val requiredParameters = mutableListOf<ToolParameterDescriptor>()
            val optionalParameters = mutableListOf<ToolParameterDescriptor>()
            schema.path("properties").fields().forEachRemaining { (name, property) ->
                val descriptor = ToolParameterDescriptor(
                    name = name,
                    description = property.path("description").asText("Notion tool parameter"),
                    type = parameterType(property)
                )
                if (name in required) requiredParameters += descriptor else optionalParameters += descriptor
            }
            ToolDescriptor(
                name = exposedName,
                description = "Notion: ${tool.path("description").asText(remoteName)}",
                requiredParameters = requiredParameters,
                optionalParameters = optionalParameters
            )
        }
    }

    suspend fun executeMcpToolCall(exposedName: String, argumentsJson: String): String {
        val remoteName = exposedToRemoteToolNames[exposedName]
            ?: exposedName.removePrefix(TOOL_PREFIX).replace('_', '-')
        val params = objectMapper.createObjectNode().apply {
            put("name", remoteName)
            set<JsonNode>("arguments", objectMapper.readTree(argumentsJson))
        }
        val root = requestMcp("tools/call", params)
        root.path("error").takeUnless { it.isMissingNode || it.isNull }?.let {
            error(it.path("message").asText("Unknown Notion MCP error"))
        }
        val content = root.path("result").path("content")
        return if (content.isArray) {
            content.filter { it.path("type").asText() == "text" }
                .joinToString("\n") { it.path("text").asText() }
        } else {
            objectMapper.writeValueAsString(root.path("result"))
        }
    }

    fun ownsTool(name: String): Boolean = name.startsWith(TOOL_PREFIX)

    private suspend fun requestMcp(method: String, params: JsonNode): JsonNode {
        ensureInitialized()
        var credentials = currentCredentials()
        var response = send(credentials.accessToken, rpcPayload(method, params), sessionId)
        if (response.status == HttpStatusCode.Unauthorized || credentialsExpireSoon(credentials)) {
            credentials = refresh(credentials)
            response = send(credentials.accessToken, rpcPayload(method, params), sessionId)
        }
        if (response.status.value !in 200..299) {
            error("Notion MCP failed (${response.status.value}): ${response.bodyAsText()}")
        }
        return parseMcpBody(response.bodyAsText())
    }

    private suspend fun ensureInitialized() = initializationMutex.withLock {
        if (initialized) return@withLock
        var credentials = currentCredentials()
        var response = send(
            credentials.accessToken,
            rpcPayload(
                "initialize",
                objectMapper.createObjectNode().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    set<JsonNode>("capabilities", objectMapper.createObjectNode())
                    set<JsonNode>("clientInfo", objectMapper.createObjectNode().apply {
                        put("name", "alfred-android")
                        put("version", "1.0")
                    })
                }
            ),
            null
        )
        if (response.status == HttpStatusCode.Unauthorized || credentialsExpireSoon(credentials)) {
            credentials = refresh(credentials)
            response = send(credentials.accessToken, rpcPayload("initialize", objectMapper.createObjectNode().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                set<JsonNode>("capabilities", objectMapper.createObjectNode())
                set<JsonNode>("clientInfo", objectMapper.createObjectNode().apply {
                    put("name", "alfred-android")
                    put("version", "1.0")
                })
            }), null)
        }
        if (response.status.value !in 200..299) {
            error("Notion MCP initialization failed (${response.status.value}): ${response.bodyAsText()}")
        }
        parseMcpBody(response.bodyAsText())
        sessionId = response.headers["Mcp-Session-Id"]
        val notification = objectMapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        val notified = send(credentials.accessToken, objectMapper.writeValueAsString(notification), sessionId)
        if (notified.status.value !in 200..299 && notified.status != HttpStatusCode.Accepted) {
            error("Notion MCP initialization acknowledgement failed (${notified.status.value}).")
        }
        initialized = true
    }

    private suspend fun send(accessToken: String, body: String, currentSessionId: String?): HttpResponse =
        httpClient.post(NotionOAuthClient.MCP_ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
            header("Accept", "application/json, text/event-stream")
            header("MCP-Protocol-Version", PROTOCOL_VERSION)
            header("User-Agent", "Alfred-Android/1.0")
            currentSessionId?.let { header("Mcp-Session-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun rpcPayload(method: String, params: JsonNode): String = objectMapper.writeValueAsString(
        objectMapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("id", System.nanoTime().toString())
            put("method", method)
            set<JsonNode>("params", params)
        }
    )

    private fun parseMcpBody(body: String): JsonNode {
        val trimmed = body.trim()
        val json = if (trimmed.startsWith("data:")) {
            trimmed.lineSequence().filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.startsWith("{") }
                ?: error("Notion MCP returned an empty event stream.")
        } else trimmed
        return objectMapper.readTree(json)
    }

    private fun currentCredentials(): NotionCredentials = credentialsProvider.getCredentials()
        ?: error("Notion is not connected. Connect it in Settings > Tools.")

    private fun credentialsExpireSoon(credentials: NotionCredentials): Boolean =
        credentials.expiresAtEpochSeconds?.let { it <= Instant.now().epochSecond + 60 } == true

    private suspend fun refresh(credentials: NotionCredentials): NotionCredentials {
        val refreshToken = credentials.refreshToken ?: error("Notion authorization expired. Reconnect Notion in Settings.")
        val values = mutableListOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to credentials.clientId
        )
        credentials.clientSecret?.let { values += "client_secret" to it }
        val response = httpClient.post(credentials.tokenEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(values.formUrlEncode())
        }
        if (response.status.value !in 200..299) {
            error("Notion authorization expired. Reconnect Notion in Settings.")
        }
        val updated = NotionOAuthClient(httpClient, objectMapper).parseCredentials(
            response.bodyAsText(), credentials.clientId, credentials.clientSecret,
            credentials.tokenEndpoint, credentials.refreshToken
        ).copy(workspaceId = credentials.workspaceId)
        credentialsProvider.onCredentialsRefreshed(updated)
        return updated
    }

    private fun parameterType(property: JsonNode): ToolParameterType {
        val enumValues = property.path("enum")
        return when {
            enumValues.isArray && enumValues.size() > 0 ->
                ToolParameterType.Enum(enumValues.map { it.asText() }.toTypedArray())
            property.path("type").asText() in setOf("integer", "number") -> ToolParameterType.Integer
            property.path("type").asText() == "boolean" -> ToolParameterType.Boolean
            else -> ToolParameterType.String
        }
    }

    private fun exposeName(remoteName: String): String {
        val normalized = remoteName.lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        return if (normalized.startsWith(TOOL_PREFIX)) normalized else TOOL_PREFIX + normalized
    }

    override fun close() = httpClient.close()

    companion object {
        const val TOOL_PREFIX = "notion_"
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
