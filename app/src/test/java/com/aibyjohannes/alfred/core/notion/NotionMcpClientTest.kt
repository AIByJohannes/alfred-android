package com.aibyjohannes.alfred.core.notion

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionMcpClientTest {
    @Test
    fun `discovers prefixed tools and calls the original remote name`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            requests += body
            when {
                body.contains("\"method\":\"initialize\"") -> respond(
                    """{"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"Notion","version":"1"}}}""",
                    HttpStatusCode.OK,
                    headersOf(
                        "Mcp-Session-Id" to listOf("session-1"),
                        HttpHeaders.ContentType to listOf("application/json")
                    )
                )
                body.contains("notifications/initialized") -> respond("", HttpStatusCode.Accepted)
                body.contains("\"method\":\"tools/list\"") -> respond(
                    """{"jsonrpc":"2.0","id":"2","result":{"tools":[{"name":"notion-search","description":"Search Notion","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Search text"}},"required":["query"]}}]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(
                    """{"jsonrpc":"2.0","id":"3","result":{"content":[{"type":"text","text":"Found it"}]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val provider = object : NotionCredentialsProvider {
            override fun getCredentials() = NotionCredentials(
                clientId = "client", accessToken = "access", refreshToken = "refresh",
                tokenEndpoint = "https://example.test/token"
            )
            override fun onCredentialsRefreshed(credentials: NotionCredentials) = Unit
        }

        val client = NotionMcpClient(provider, HttpClient(engine))
        val descriptor = client.getMcpTools().single()
        assertEquals("notion_search", descriptor.name)
        assertTrue(client.ownsTool(descriptor.name))
        assertEquals("Found it", client.executeMcpToolCall(descriptor.name, """{"query":"roadmap"}"""))
        assertTrue(requests.last().contains("\"name\":\"notion-search\""))
        client.close()
    }
}
