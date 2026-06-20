package com.aibyjohannes.alfred.core.ticktick

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.util.Base64

class TickTickClientTest {

    private val testCredentials = TickTickCredentials(
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token"
    )

    private val credentialsProvider = object : TickTickCredentialsProvider {
        var currentCreds: TickTickCredentials? = testCredentials
        var refreshCount = 0

        override fun getCredentials(): TickTickCredentials? = currentCreds

        override fun onCredentialsRefreshed(credentials: TickTickCredentials) {
            currentCreds = credentials
            refreshCount++
        }
    }

    @Test
    fun `getMcpTools parses JSON-RPC tools list response`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://mcp.ticktick.com", request.url.toString())
            assertEquals("Bearer test-access-token", request.headers["Authorization"])
            assertEquals("2024-11-05", request.headers["Mcp-Protocol-Version"])
            
            val reqBody = requestBodyText(request)
            assertTrue(reqBody.contains("\"method\":\"tools/list\""))
            
            respond(
                content = """
                {
                  "jsonrpc": "2.0",
                  "result": {
                    "tools": [
                      {
                        "name": "create_task",
                        "description": "Create a new task",
                        "inputSchema": {
                          "type": "object",
                          "properties": {
                            "title": { "type": "string", "description": "The task title" },
                            "priority": { "type": "integer" },
                            "status": { "type": "string", "enum": ["open", "closed"] }
                          },
                          "required": ["title"]
                        }
                      }
                    ]
                  },
                  "id": "1"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val client = TickTickClient(credentialsProvider, httpClient)

        val tools = client.getMcpTools()
        assertEquals(1, tools.size)
        val tool = tools[0]
        assertEquals("create_task", tool.name)
        assertEquals("Create a new task", tool.description)
        assertEquals(1, tool.requiredParameters.size)
        assertEquals("title", tool.requiredParameters[0].name)
        assertEquals(2, tool.optionalParameters.size)
    }

    @Test
    fun `executeMcpToolCall parses JSON-RPC call response and returns text`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://mcp.ticktick.com", request.url.toString())
            
            val reqBody = requestBodyText(request)
            assertTrue(reqBody.contains("\"method\":\"tools/call\""))
            assertTrue(reqBody.contains("\"name\":\"create_task\""))
            assertTrue(reqBody.contains("\"title\":\"Buy milk\""))

            respond(
                content = """
                {
                  "jsonrpc": "2.0",
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "Success response text"
                      }
                    ]
                  },
                  "id": "1"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val client = TickTickClient(credentialsProvider, httpClient)

        val result = client.executeMcpToolCall("create_task", "{\"title\":\"Buy milk\"}")
        assertEquals("Success response text", result)
    }

    @Test
    fun `token refresh happens on 401 and request retries`() = runTest {
        var callCount = 0
        var refreshBody: String? = null
        val mockEngine = MockEngine { request ->
            callCount++
            when {
                request.url.toString() == "https://mcp.ticktick.com" && request.headers["Authorization"] == "Bearer test-access-token" -> {
                    respond(
                        content = "Unauthorized",
                        status = HttpStatusCode.Unauthorized
                    )
                }
                request.url.toString() == "https://ticktick.com/oauth/token" -> {
                    refreshBody = requestBodyText(request)
                    respond(
                        content = "{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\"}",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
                request.url.toString() == "https://mcp.ticktick.com" && request.headers["Authorization"] == "Bearer new-access-token" -> {
                    respond(
                        content = """
                        {
                          "jsonrpc": "2.0",
                          "result": {
                            "tools": []
                          },
                          "id": "1"
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
                else -> respond("Error", HttpStatusCode.BadRequest)
            }
        }
        val httpClient = HttpClient(mockEngine)
        val client = TickTickClient(credentialsProvider, httpClient)

        val tools = client.getMcpTools()
        assertEquals(0, tools.size)
        assertEquals(3, callCount)
        assertEquals(1, credentialsProvider.refreshCount)
        assertEquals("new-access-token", credentialsProvider.currentCreds?.accessToken)
        assertEquals("new-refresh-token", credentialsProvider.currentCreds?.refreshToken)
        assertEquals("grant_type=refresh_token&refresh_token=test-refresh-token", refreshBody)
    }

    @Test
    fun `exchangeCodeForTokens form encodes authorization code and oauth fields`() = runTest {
        var tokenBody: String? = null
        val mockEngine = MockEngine { request ->
            assertEquals("https://ticktick.com/oauth/token", request.url.toString())
            tokenBody = requestBodyText(request)
            respond(
                content = "{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\"}",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)

        val result = TickTickClient.exchangeCodeForTokens(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            code = "abc/def+ghi=jkl",
            httpClient = httpClient
        )

        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)
        assertEquals(
            "code=abc%2Fdef%2Bghi%3Djkl&grant_type=authorization_code&scope=tasks%3Aread+tasks%3Awrite&redirect_uri=http%3A%2F%2Flocalhost%3A54321%2Fcallback",
            tokenBody
        )
    }

    @Test
    fun `exchangeCodeForTokens accepts access token without refresh token`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "{\"access_token\":\"new-access-token\"}",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)

        val result = TickTickClient.exchangeCodeForTokens(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            code = "code",
            httpClient = httpClient
        )

        assertEquals("new-access-token", result.accessToken)
        assertNull(result.refreshToken)
    }

    @Test
    fun `exchangeCodeForTokens reports token exchange failure without parsing tokens`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "{\"error\":\"invalid_grant\"}",
                status = HttpStatusCode.BadRequest,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)

        try {
            TickTickClient.exchangeCodeForTokens(
                clientId = "test-client-id",
                clientSecret = "test-client-secret",
                code = "code",
                httpClient = httpClient
            )
            fail("Expected token exchange to fail")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Token exchange failed: 400") == true)
            assertTrue(e.message?.contains("Response missing") != true)
        }
    }

    private fun requestBodyText(request: HttpRequestData): String {
        return when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            else -> body.toString()
        }
    }
}
