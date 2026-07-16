package com.aibyjohannes.alfred.core.notion

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class NotionOAuthPending(
    val clientId: String,
    val clientSecret: String?,
    val tokenEndpoint: String,
    val redirectUri: String,
    val codeVerifier: String,
    val state: String,
    val authorizationUrl: String
)

class NotionOAuthClient(
    private val httpClient: HttpClient = HttpClient(OkHttp),
    private val objectMapper: ObjectMapper = ObjectMapper()
) : AutoCloseable {

    suspend fun beginAuthorization(redirectUri: String): NotionOAuthPending {
        val resource = httpClient.get(PROTECTED_RESOURCE_METADATA).bodyAsText().let(objectMapper::readTree)
        val authorizationServer = resource.path("authorization_servers").firstOrNull()?.asText()
            ?: error("Notion did not advertise an OAuth authorization server.")
        val metadataUrl = authorizationServer.trimEnd('/') + "/.well-known/oauth-authorization-server"
        val metadata = httpClient.get(metadataUrl).bodyAsText().let(objectMapper::readTree)
        val authorizationEndpoint = metadata.path("authorization_endpoint").asText().takeIf(String::isNotBlank)
            ?: error("Notion OAuth metadata is missing the authorization endpoint.")
        val tokenEndpoint = metadata.path("token_endpoint").asText().takeIf(String::isNotBlank)
            ?: error("Notion OAuth metadata is missing the token endpoint.")
        val registrationEndpoint = metadata.path("registration_endpoint").asText().takeIf(String::isNotBlank)
            ?: error("Notion OAuth metadata is missing dynamic client registration.")

        val registrationPayload = objectMapper.createObjectNode().apply {
            put("client_name", "Alfred Android")
            put("client_uri", "https://github.com/AIByJohannes/alfred-android")
            putArray("redirect_uris").add(redirectUri)
            putArray("grant_types").add("authorization_code").add("refresh_token")
            putArray("response_types").add("code")
            put("token_endpoint_auth_method", "none")
        }
        val registrationResponse = httpClient.post(registrationEndpoint) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(objectMapper.writeValueAsString(registrationPayload))
        }
        if (registrationResponse.status.value !in 200..299) {
            error("Notion client registration failed (${registrationResponse.status.value}): ${registrationResponse.bodyAsText()}")
        }
        val registration = objectMapper.readTree(registrationResponse.bodyAsText())
        val clientId = registration.path("client_id").asText().takeIf(String::isNotBlank)
            ?: error("Notion client registration returned no client ID.")
        val clientSecret = registration.path("client_secret").asText().takeIf(String::isNotBlank)
        val codeVerifier = randomUrlSafe(32)
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        )
        val state = randomUrlSafe(32)
        val authorizationUrl = URLBuilder(authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("prompt", "consent")
        }.buildString()

        return NotionOAuthPending(
            clientId = clientId,
            clientSecret = clientSecret,
            tokenEndpoint = tokenEndpoint,
            redirectUri = redirectUri,
            codeVerifier = codeVerifier,
            state = state,
            authorizationUrl = authorizationUrl
        )
    }

    suspend fun exchangeCode(code: String, pending: NotionOAuthPending): NotionCredentials {
        val values = mutableListOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to pending.clientId,
            "redirect_uri" to pending.redirectUri,
            "code_verifier" to pending.codeVerifier
        )
        pending.clientSecret?.let { values += "client_secret" to it }
        val response = httpClient.post(pending.tokenEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            header("User-Agent", "Alfred-Android/1.0")
            setBody(values.formUrlEncode())
        }
        if (response.status.value !in 200..299) {
            error("Notion token exchange failed (${response.status.value}): ${response.bodyAsText()}")
        }
        return parseCredentials(response.bodyAsText(), pending.clientId, pending.clientSecret, pending.tokenEndpoint)
    }

    internal fun parseCredentials(
        body: String,
        clientId: String,
        clientSecret: String?,
        tokenEndpoint: String,
        previousRefreshToken: String? = null
    ): NotionCredentials {
        val node = objectMapper.readTree(body)
        val accessToken = node.path("access_token").asText().takeIf(String::isNotBlank)
            ?: error("Notion token response contained no access token.")
        val expiresIn = node.path("expires_in").asLong(0).takeIf { it > 0 }
        return NotionCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
            accessToken = accessToken,
            refreshToken = node.path("refresh_token").asText().takeIf(String::isNotBlank) ?: previousRefreshToken,
            tokenEndpoint = tokenEndpoint,
            expiresAtEpochSeconds = expiresIn?.let { Instant.now().epochSecond + it },
            workspaceId = node.path("workspace_id").asText().takeIf(String::isNotBlank)
        )
    }

    override fun close() = httpClient.close()

    private fun randomUrlSafe(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val MCP_ENDPOINT = "https://mcp.notion.com/mcp"
        const val PROTECTED_RESOURCE_METADATA =
            "https://mcp.notion.com/mcp/.well-known/oauth-protected-resource"
        const val REDIRECT_URI = "com.aibyjohannes.alfred:/oauth/notion"
    }
}
