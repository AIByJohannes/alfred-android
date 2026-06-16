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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    fun `resolveDueDate resolves relative and ISO dates`() {
        val client = TickTickClient(credentialsProvider)
        val zoneId = ZoneId.of("UTC")
        val today = LocalDate.now(zoneId)

        val (todayResolved, isAllDayToday) = client.resolveDueDate("today", zoneId)
        assertTrue(isAllDayToday)
        assertEquals(today.atStartOfDay(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000+0000")), todayResolved)

        val (tomorrowResolved, isAllDayTomorrow) = client.resolveDueDate("tomorrow", zoneId)
        assertTrue(isAllDayTomorrow)
        assertEquals(today.plusDays(1).atStartOfDay(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000+0000")), tomorrowResolved)

        val (yesterdayResolved, isAllDayYesterday) = client.resolveDueDate("yesterday", zoneId)
        assertTrue(isAllDayYesterday)
        assertEquals(today.minusDays(1).atStartOfDay(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000+0000")), yesterdayResolved)

        val (fixedResolved, isAllDayFixed) = client.resolveDueDate("2026-05-23", zoneId)
        assertTrue(isAllDayFixed)
        assertEquals("2026-05-23T00:00:00.000+0000", fixedResolved)
    }

    @Test
    fun `getProjects returns correct response`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://api.ticktick.com/open/v1/project", request.url.toString())
            assertEquals("Bearer test-access-token", request.headers["Authorization"])
            respond(
                content = "[{\"id\":\"project1\",\"name\":\"Inbox\"}]",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val client = TickTickClient(credentialsProvider, httpClient)

        val result = client.getProjects()
        assertEquals("[{\"id\":\"project1\",\"name\":\"Inbox\"}]", result)
    }

    @Test
    fun `token refresh happens on 401 and request retries`() = runTest {
        var callCount = 0
        var refreshBody: String? = null
        val mockEngine = MockEngine { request ->
            callCount++
            when {
                request.url.toString() == "https://api.ticktick.com/open/v1/project" && request.headers["Authorization"] == "Bearer test-access-token" -> {
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
                request.url.toString() == "https://api.ticktick.com/open/v1/project" && request.headers["Authorization"] == "Bearer new-access-token" -> {
                    respond(
                        content = "[{\"id\":\"project1\",\"name\":\"Inbox\"}]",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
                else -> respond("Error", HttpStatusCode.BadRequest)
            }
        }
        val httpClient = HttpClient(mockEngine)
        val client = TickTickClient(credentialsProvider, httpClient)

        val result = client.getProjects()
        assertEquals("[{\"id\":\"project1\",\"name\":\"Inbox\"}]", result)
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
