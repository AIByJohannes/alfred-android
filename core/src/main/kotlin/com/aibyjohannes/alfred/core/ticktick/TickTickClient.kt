package com.aibyjohannes.alfred.core.ticktick

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import java.util.Base64
import java.util.concurrent.TimeUnit

class TickTickClient(
    private val credentialsProvider: TickTickCredentialsProvider,
    private val httpClient: HttpClient = createDefaultClient()
) : AutoCloseable {

    private val objectMapper = ObjectMapper()

    private fun getCredentialsOrThrow(): TickTickCredentials {
        return credentialsProvider.getCredentials()
            ?: throw IllegalStateException("TickTick is not configured. Please configure TickTick in settings.")
    }

    private suspend fun requestMcp(
        method: String,
        params: com.fasterxml.jackson.databind.JsonNode
    ): String {
        val payload = objectMapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis().toString())
            put("method", method)
            set<com.fasterxml.jackson.databind.JsonNode>("params", params)
        }
        val bodyJson = objectMapper.writeValueAsString(payload)

        val creds = getCredentialsOrThrow()
        val url = "https://mcp.ticktick.com"

        val response = tryRequestMcp(url, creds.accessToken, bodyJson)

        if (response.status == HttpStatusCode.Unauthorized) {
            if (creds.refreshToken.isNullOrBlank()) {
                throw Exception("TickTick token expired. Please reconnect TickTick in settings.")
            }
            val newCreds = refreshToken(creds)
            if (newCreds != null) {
                val retryResponse = tryRequestMcp(url, newCreds.accessToken, bodyJson)
                if (retryResponse.status.value >= 300) {
                    throw Exception("TickTick MCP error after refresh: ${retryResponse.status.value} ${retryResponse.bodyAsText()}")
                }
                return retryResponse.bodyAsText()
            } else {
                throw Exception("TickTick token expired and refresh failed.")
            }
        } else if (response.status.value >= 300) {
            throw Exception("TickTick MCP error: ${response.status.value} ${response.bodyAsText()}")
        }

        return response.bodyAsText()
    }

    private suspend fun tryRequestMcp(
        url: String,
        accessToken: String,
        bodyJson: String
    ): HttpResponse {
        return httpClient.request(url) {
            this.method = HttpMethod.Post
            header("Authorization", "Bearer $accessToken")
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("Mcp-Protocol-Version", "2024-11-05")
            setBody(bodyJson)
        }
    }

    private suspend fun refreshToken(creds: TickTickCredentials): TickTickCredentials? {
        val refreshToken = creds.refreshToken ?: return null
        val basicAuth = Base64.getEncoder()
            .encodeToString("${creds.clientId}:${creds.clientSecret}".toByteArray())

        val response = try {
            httpClient.request("https://ticktick.com/oauth/token") {
                this.method = HttpMethod.Post
                header("Authorization", "Basic $basicAuth")
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(
                    listOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken
                    ).formUrlEncode()
                )
            }
        } catch (e: Exception) {
            return null
        }

        if (response.status != HttpStatusCode.OK) {
            return null
        }

        val body = response.bodyAsText()
        val node = objectMapper.readTree(body)
        val newAccessToken = node.path("access_token").asText(null)
        val newRefreshToken = node.path("refresh_token").asText(creds.refreshToken)

        if (newAccessToken.isNullOrBlank()) {
            return null
        }

        val updatedCreds = TickTickCredentials(
            clientId = creds.clientId,
            clientSecret = creds.clientSecret,
            accessToken = newAccessToken,
            refreshToken = newRefreshToken
        )
        credentialsProvider.onCredentialsRefreshed(updatedCreds)
        return updatedCreds
    }

    suspend fun getMcpTools(): List<ToolDescriptor> {
        val responseBody = try {
            requestMcp("tools/list", objectMapper.createObjectNode())
        } catch (e: Exception) {
            return emptyList()
        }

        val rootNode = objectMapper.readTree(responseBody)
        val toolsNode = rootNode.path("result").path("tools")
        if (!toolsNode.isArray) {
            return emptyList()
        }

        val descriptors = mutableListOf<ToolDescriptor>()
        for (toolNode in toolsNode) {
            val name = toolNode.path("name").asText("")
            val description = toolNode.path("description").asText("")
            if (name.isBlank()) continue

            val requiredParams = mutableListOf<ToolParameterDescriptor>()
            val optionalParams = mutableListOf<ToolParameterDescriptor>()

            val inputSchema = toolNode.path("inputSchema")
            val requiredSet = mutableSetOf<String>()
            val requiredNode = inputSchema.path("required")
            if (requiredNode.isArray) {
                for (r in requiredNode) {
                    requiredSet.add(r.asText())
                }
            }

            val propertiesNode = inputSchema.path("properties")
            if (propertiesNode.isObject) {
                val fields = propertiesNode.fieldNames()
                while (fields.hasNext()) {
                    val fieldName = fields.next()
                    val propNode = propertiesNode.path(fieldName)
                    val propDesc = propNode.path("description").asText("")
                    val propTypeStr = propNode.path("type").asText("string")
                    val enumNode = propNode.path("enum")

                    val paramType = when {
                        enumNode.isArray && enumNode.size() > 0 -> {
                            val enumValues = mutableListOf<String>()
                            for (e in enumNode) {
                                enumValues.add(e.asText())
                            }
                            ToolParameterType.Enum(enumValues.toTypedArray())
                        }
                        propTypeStr == "integer" || propTypeStr == "number" -> ToolParameterType.Integer
                        propTypeStr == "boolean" -> ToolParameterType.Boolean
                        else -> ToolParameterType.String
                    }

                    val paramDescriptor = ToolParameterDescriptor(fieldName, propDesc, paramType)
                    if (fieldName in requiredSet) {
                        requiredParams.add(paramDescriptor)
                    } else {
                        optionalParams.add(paramDescriptor)
                    }
                }
            }

            descriptors.add(
                ToolDescriptor(
                    name = name,
                    description = description,
                    requiredParameters = requiredParams,
                    optionalParameters = optionalParams
                )
            )
        }
        return descriptors
    }

    suspend fun executeMcpToolCall(name: String, argumentsJson: String): String {
        val paramsNode = objectMapper.createObjectNode()
        paramsNode.put("name", name)
        paramsNode.set<com.fasterxml.jackson.databind.JsonNode>("arguments", objectMapper.readTree(argumentsJson))

        val responseBody = requestMcp("tools/call", paramsNode)
        val rootNode = objectMapper.readTree(responseBody)

        val errorNode = rootNode.path("error")
        if (!errorNode.isMissingNode && !errorNode.isNull) {
            val message = errorNode.path("message").asText("Unknown MCP error")
            throw Exception(message)
        }

        val contentNode = rootNode.path("result").path("content")
        if (contentNode.isArray) {
            val textBuilder = StringBuilder()
            for (contentItem in contentNode) {
                val type = contentItem.path("type").asText()
                if (type == "text") {
                    textBuilder.append(contentItem.path("text").asText())
                }
            }
            return textBuilder.toString()
        }

        return responseBody
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val OAUTH_REDIRECT_URI = "http://localhost:54321/callback"
        const val OAUTH_SCOPE = "tasks:read tasks:write"

        fun createDefaultClient(): HttpClient {
            return HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(15, TimeUnit.SECONDS)
                        readTimeout(60, TimeUnit.SECONDS)
                        writeTimeout(60, TimeUnit.SECONDS)
                    }
                }
            }
        }

        suspend fun exchangeCodeForTokens(
            clientId: String,
            clientSecret: String,
            code: String,
            httpClient: HttpClient = createDefaultClient()
        ): TickTickCredentials {
            val basicAuth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
            val mapper = ObjectMapper()
            val response = httpClient.request("https://ticktick.com/oauth/token") {
                this.method = HttpMethod.Post
                header("Authorization", "Basic $basicAuth")
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(
                    listOf(
                        "code" to code,
                        "grant_type" to "authorization_code",
                        "scope" to OAUTH_SCOPE,
                        "redirect_uri" to OAUTH_REDIRECT_URI
                    ).formUrlEncode()
                )
            }

            if (response.status != HttpStatusCode.OK) {
                throw Exception("Token exchange failed: ${response.status.value} ${response.bodyAsText()}")
            }

            val body = response.bodyAsText()
            val node = mapper.readTree(body)
            val accessToken = node.path("access_token").asText(null)
            val refreshToken = node.path("refresh_token").asText(null)

            if (accessToken.isNullOrBlank()) {
                throw Exception("Response missing access token")
            }

            return TickTickCredentials(clientId, clientSecret, accessToken, refreshToken)
        }
    }
}
