package com.aibyjohannes.alfred.core.search

interface ObsidianClient {
    suspend fun search(query: String): Result<String>
    suspend fun read(path: String): Result<String>
    suspend fun write(path: String, content: String, append: Boolean): Result<String>
}
