package com.aibyjohannes.alfred.core.ticktick

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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

    private suspend fun request(
        method: String,
        endpoint: String,
        bodyJson: String? = null
    ): String {
        val creds = getCredentialsOrThrow()
        val url = "https://api.ticktick.com/open/v1/$endpoint"

        val response = tryRequest(method, url, creds.accessToken, bodyJson)

        if (response.status == HttpStatusCode.Unauthorized) {
            // Token expired, refresh it
            val newCreds = refreshToken(creds)
            if (newCreds != null) {
                val retryResponse = tryRequest(method, url, newCreds.accessToken, bodyJson)
                if (retryResponse.status.value >= 300) {
                    throw Exception("TickTick API error after refresh: ${retryResponse.status.value} ${retryResponse.bodyAsText()}")
                }
                return retryResponse.bodyAsText()
            } else {
                throw Exception("TickTick token expired and refresh failed.")
            }
        } else if (response.status.value >= 300) {
            throw Exception("TickTick API error: ${response.status.value} ${response.bodyAsText()}")
        }

        return response.bodyAsText()
    }

    private suspend fun tryRequest(
        method: String,
        url: String,
        accessToken: String,
        bodyJson: String? = null
    ): HttpResponse {
        return httpClient.request(url) {
            this.method = HttpMethod.parse(method)
            header("Authorization", "Bearer $accessToken")
            if (bodyJson != null) {
                header("Content-Type", "application/json")
                setBody(bodyJson)
            }
        }
    }

    private suspend fun refreshToken(creds: TickTickCredentials): TickTickCredentials? {
        val basicAuth = Base64.getEncoder()
            .encodeToString("${creds.clientId}:${creds.clientSecret}".toByteArray())

        val response = try {
            httpClient.request("https://ticktick.com/oauth/token") {
                this.method = HttpMethod.Post
                header("Authorization", "Basic $basicAuth")
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody("grant_type=refresh_token&refresh_token=${creds.refreshToken}")
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

    // --- Task Methods ---

    suspend fun getTasks(): String {
        val projectsJson = getProjects()
        val projectsNode = objectMapper.readTree(projectsJson)

        val allTasks = mutableListOf<Map<String, Any?>>()
        val projectIds = mutableListOf(Pair("inbox", "Inbox"))

        if (projectsNode.isArray) {
            for (proj in projectsNode) {
                val closed = proj.path("closed").asBoolean(false)
                if (!closed) {
                    val id = proj.path("id").asText()
                    val name = proj.path("name").asText()
                    if (id != "inbox") {
                        projectIds.add(Pair(id, name))
                    }
                }
            }
        }

        for ((projectId, projectName) in projectIds) {
            try {
                val dataJson = getProjectData(projectId)
                val dataNode = objectMapper.readTree(dataJson)
                val tasksNode = dataNode.path("tasks")
                if (tasksNode.isArray) {
                    for (task in tasksNode) {
                        val taskMap = objectMapper.readValue(
                            objectMapper.writeValueAsString(task),
                            Map::class.java
                        ).toMutableMap() as MutableMap<String, Any?>
                        taskMap["projectId"] = taskMap["projectId"] ?: projectId
                        taskMap["project_name"] = projectName
                        allTasks.add(taskMap)
                    }
                }
            } catch (_: Exception) {
                // Ignore single project errors (same as Python script)
            }
        }

        return objectMapper.writeValueAsString(allTasks)
    }

    suspend fun getTask(projectId: String, taskId: String): String {
        return request("GET", "project/$projectId/task/$taskId")
    }

    suspend fun createTask(
        title: String,
        projectId: String? = null,
        content: String? = null,
        dueDate: String? = null,
        priority: Int? = null,
        tags: List<String>? = null
    ): String {
        val payload = mutableMapOf<String, Any?>("title" to title)
        if (projectId != null) payload["projectId"] = projectId
        if (content != null) payload["content"] = content
        if (priority != null) payload["priority"] = priority
        if (tags != null) payload["tags"] = tags

        if (dueDate != null) {
            val (resolvedDue, isAllDay) = resolveDueDate(dueDate)
            payload["dueDate"] = resolvedDue
            payload["isAllDay"] = isAllDay
        }

        return request("POST", "task", serializeMap(payload))
    }

    suspend fun updateTask(
        taskId: String,
        projectId: String,
        newProjectId: String? = null,
        title: String? = null,
        content: String? = null,
        dueDate: String? = null,
        priority: Int? = null,
        tags: List<String>? = null,
        items: List<Map<String, Any?>>? = null
    ): String {
        val currentTaskJson = getTask(projectId, taskId)
        val payload = objectMapper.readValue(
            currentTaskJson,
            Map::class.java
        ).toMutableMap() as MutableMap<String, Any?>

        if (title != null) payload["title"] = title
        if (content != null) payload["content"] = content
        if (priority != null) payload["priority"] = priority
        if (tags != null) payload["tags"] = tags
        if (items != null) payload["items"] = items

        if (dueDate != null) {
            val (resolvedDue, isAllDay) = resolveDueDate(dueDate)
            payload["dueDate"] = resolvedDue
            payload["isAllDay"] = isAllDay
        }

        var finalProjectId = projectId
        if (newProjectId != null && newProjectId != projectId) {
            val movePayload = listOf(
                mapOf(
                    "taskId" to taskId,
                    "fromProjectId" to projectId,
                    "toProjectId" to newProjectId
                )
            )
            request("POST", "task/move", objectMapper.writeValueAsString(movePayload))
            finalProjectId = newProjectId
        }

        payload["projectId"] = finalProjectId
        payload["id"] = taskId

        return request("POST", "task/$taskId", serializeMap(payload))
    }

    suspend fun completeTask(projectId: String, taskId: String): String {
        request("POST", "project/$projectId/task/$taskId/complete")
        return "{\"ok\": true, \"id\": \"$taskId\", \"projectId\": \"$projectId\"}"
    }

    suspend fun deleteTask(projectId: String, taskId: String): String {
        request("DELETE", "project/$projectId/task/$taskId")
        return "{\"ok\": true, \"id\": \"$taskId\", \"projectId\": \"$projectId\"}"
    }

    // --- Subtask Methods ---

    suspend fun listSubtasks(projectId: String, taskId: String): List<Map<String, Any?>> {
        val taskJson = getTask(projectId, taskId)
        val node = objectMapper.readTree(taskJson)
        val itemsNode = node.path("items")
        if (itemsNode.isMissingNode || !itemsNode.isArray) {
            return emptyList()
        }
        return objectMapper.convertValue(itemsNode, List::class.java) as List<Map<String, Any?>>
    }

    suspend fun createSubtask(projectId: String, taskId: String, title: String): String {
        val items = listSubtasks(projectId, taskId).toMutableList()
        items.add(mapOf("title" to title, "status" to 0))
        return updateTask(taskId, projectId, items = items)
    }

    suspend fun completeSubtask(projectId: String, taskId: String, indexOrTitle: String): String {
        val items = listSubtasks(projectId, taskId).toMutableList()
        if (items.isEmpty()) {
            throw Exception("Task has no subtasks.")
        }

        val targetIdx = findSubtaskIndex(items, indexOrTitle)
        if (targetIdx == -1) {
            throw Exception("No subtask found matching: $indexOrTitle")
        }

        val updatedItem = items[targetIdx].toMutableMap()
        updatedItem["status"] = 2
        items[targetIdx] = updatedItem

        return updateTask(taskId, projectId, items = items)
    }

    suspend fun deleteSubtask(projectId: String, taskId: String, indexOrTitle: String): String {
        val items = listSubtasks(projectId, taskId).toMutableList()
        if (items.isEmpty()) {
            throw Exception("Task has no subtasks.")
        }

        val targetIdx = findSubtaskIndex(items, indexOrTitle)
        if (targetIdx == -1) {
            throw Exception("No subtask found matching: $indexOrTitle")
        }

        items.removeAt(targetIdx)
        return updateTask(taskId, projectId, items = items)
    }

    // --- Project Methods ---

    suspend fun getProjects(): String {
        return request("GET", "project")
    }

    suspend fun getProjectData(projectId: String): String {
        return request("GET", "project/$projectId/data")
    }

    suspend fun createProject(name: String, color: String? = null): String {
        val payload = mutableMapOf<String, Any?>("name" to name)
        if (color != null) payload["color"] = color
        return request("POST", "project", serializeMap(payload))
    }

    suspend fun deleteProject(projectId: String): String {
        request("DELETE", "project/$projectId")
        return "{\"ok\": true, \"projectId\": \"$projectId\"}"
    }

    // --- Helpers ---

    private fun findSubtaskIndex(items: List<Map<String, Any?>>, indexOrTitle: String): Int {
        val parsedIdx = indexOrTitle.toIntOrNull()
        if (parsedIdx != null && parsedIdx >= 1 && parsedIdx <= items.size) {
            return parsedIdx - 1
        }

        val query = normalizeQuery(indexOrTitle)
        for (i in items.indices) {
            val title = normalizeQuery(items[i]["title"]?.toString() ?: "")
            if (title == query) {
                return i
            }
        }

        for (i in items.indices) {
            val title = normalizeQuery(items[i]["title"]?.toString() ?: "")
            if (title.contains(query)) {
                return i
            }
        }

        return -1
    }

    private fun normalizeQuery(value: String): String {
        return value.trim().lowercase().split("\\s+".toRegex()).joinToString(" ")
    }

    fun resolveDueDate(dueStr: String, zoneId: ZoneId = ZoneId.systemDefault()): Pair<String, Boolean> {
        val valStr = dueStr.trim().lowercase()
        val today = LocalDate.now(zoneId)
        val targetDate = when (valStr) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            "yesterday" -> today.minusDays(1)
            else -> {
                try {
                    LocalDate.parse(dueStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (targetDate != null) {
            val zdt = targetDate.atStartOfDay(zoneId).withZoneSameInstant(ZoneId.of("UTC"))
            val formatted = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000+0000"))
            return Pair(formatted, true)
        }

        return try {
            val zdt = ZonedDateTime.parse(dueStr).withZoneSameInstant(ZoneId.of("UTC"))
            val formatted = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000+0000"))
            Pair(formatted, false)
        } catch (_: Exception) {
            Pair(dueStr, false)
        }
    }

    private fun serializeMap(map: Map<String, Any?>): String {
        val cleanMap = map.filterValues { it != null }
        return objectMapper.writeValueAsString(cleanMap)
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
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
                setBody("code=$code&grant_type=authorization_code&scope=tasks:read%20tasks:write&redirect_uri=http://localhost:54321/callback")
            }

            if (response.status != HttpStatusCode.OK) {
                throw Exception("Token exchange failed: ${response.status.value} ${response.bodyAsText()}")
            }

            val body = response.bodyAsText()
            val node = mapper.readTree(body)
            val accessToken = node.path("access_token").asText(null)
            val refreshToken = node.path("refresh_token").asText(null)

            if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                throw Exception("Response missing tokens")
            }

            return TickTickCredentials(clientId, clientSecret, accessToken, refreshToken)
        }
    }
}
