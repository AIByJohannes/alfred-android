package com.aibyjohannes.alfred.core.image

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import com.aibyjohannes.alfred.core.openrouter.OpenRouterAttribution

class OpenRouterImageClientTest {
    @Test
    fun `generate posts prompt and decodes generated image`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertEquals(OpenRouterAttribution.APP_URL, request.headers["HTTP-Referer"])
            assertEquals(OpenRouterAttribution.APP_TITLE, request.headers["X-OpenRouter-Title"])
            respond(
                """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}","media_type":"image/webp"}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        })
        val imageClient = OpenRouterImageClient("test-key", client = client)

        val image = imageClient.generate("a small robot").getOrThrow()

        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
        assertEquals("image/webp", image.mediaType)
        imageClient.close()
    }
}
