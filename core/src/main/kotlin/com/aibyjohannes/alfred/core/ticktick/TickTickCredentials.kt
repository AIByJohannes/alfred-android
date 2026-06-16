package com.aibyjohannes.alfred.core.ticktick

data class TickTickCredentials(
    val clientId: String,
    val clientSecret: String,
    val accessToken: String,
    val refreshToken: String?
)

interface TickTickCredentialsProvider {
    fun getCredentials(): TickTickCredentials?
    fun onCredentialsRefreshed(credentials: TickTickCredentials)
}
