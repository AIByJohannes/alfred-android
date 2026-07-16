package com.aibyjohannes.alfred.core.notion

data class NotionCredentials(
    val clientId: String,
    val clientSecret: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenEndpoint: String,
    val expiresAtEpochSeconds: Long? = null,
    val workspaceId: String? = null
)

interface NotionCredentialsProvider {
    fun getCredentials(): NotionCredentials?
    fun onCredentialsRefreshed(credentials: NotionCredentials)
}
