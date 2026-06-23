package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.search.ObsidianClient
import com.aibyjohannes.alfred.core.search.WebSearchClient
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterChatEngineObsidianTest {

    private val mockWebSearch = object : WebSearchClient {
        override suspend fun search(query: String): Result<String> = Result.success("")
    }

    @Test
    fun `obsidian tools are registered when client is present`() {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("buildAlfredToolDescriptors") &&
            it.parameterCount == 1 &&
            it.parameterTypes[0] == java.util.List::class.java
        }
        method.isAccessible = true
        val tools = method.invoke(engine, emptyList<Any>()) as List<*>

        val names = tools.map { it?.javaClass?.getMethod("getName")?.invoke(it) as String }
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_LIST_FOLDER_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_READ_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_CREATE_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_UPDATE_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_RENAME_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_DELETE_TOOL))
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL))
    }

    @Test
    fun `obsidian tools are not registered when client is null`() {
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = null
        )

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("buildAlfredToolDescriptors") &&
            it.parameterCount == 1 &&
            it.parameterTypes[0] == java.util.List::class.java
        }
        method.isAccessible = true
        val tools = method.invoke(engine, emptyList<Any>()) as List<*>

        val names = tools.map { it?.javaClass?.getMethod("getName")?.invoke(it) as String }
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_LIST_FOLDER_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_READ_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_CREATE_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_UPDATE_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_RENAME_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_DELETE_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL))
    }

    @Test
    fun `search tool schema includes directory, sort_by and order optional parameters`() {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("buildAlfredToolDescriptors") &&
            it.parameterCount == 1 &&
            it.parameterTypes[0] == java.util.List::class.java
        }
        method.isAccessible = true
        val tools = method.invoke(engine, emptyList<Any>()) as List<*>

        val searchTool = tools.first {
            it?.javaClass?.getMethod("getName")?.invoke(it) == OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL
        }!!
        val optionalMethod = searchTool.javaClass.getMethod("getOptionalParameters")
        val optionalParams = optionalMethod.invoke(searchTool) as List<*>
        val optionalParamNames = optionalParams.map {
            it?.javaClass?.getMethod("getName")?.invoke(it) as String
        }
        assertTrue("directory optional param missing", optionalParamNames.contains("directory"))
        assertTrue("sort_by optional param missing", optionalParamNames.contains("sort_by"))
        assertTrue("order optional param missing", optionalParamNames.contains("order"))
    }

    @Test
    fun `extractObsidianSearchArgs parses query, directory, sort_by and order`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockWebSearch)
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractObsidianSearchArgs")
        }
        method.isAccessible = true

        // Full args
        val argsJson = """{"query":"meeting note","directory":"Daily","sort_by":"modified","order":"asc"}"""
        val result = method.invoke(engine, argsJson)
        assertNotNull(result)
        val getQuery = result!!.javaClass.getMethod("getQuery")
        val getDirectory = result.javaClass.getMethod("getDirectory")
        val getSortBy = result.javaClass.getMethod("getSortBy")
        val getOrder = result.javaClass.getMethod("getOrder")
        assertEquals("meeting note", getQuery.invoke(result))
        assertEquals("Daily", getDirectory.invoke(result))
        assertEquals("modified", getSortBy.invoke(result))
        assertEquals("asc", getOrder.invoke(result))

        // Minimal args — defaults applied
        val minimalResult = method.invoke(engine, """{"query":"test"}""")
        assertNotNull(minimalResult)
        assertEquals("score", minimalResult!!.javaClass.getMethod("getSortBy").invoke(minimalResult))
        assertEquals("desc", minimalResult.javaClass.getMethod("getOrder").invoke(minimalResult))
        assertNull(minimalResult.javaClass.getMethod("getDirectory").invoke(minimalResult))

        // Missing query → null
        val nullResult = method.invoke(engine, """{}""")
        assertNull(nullResult)
    }

    @Test
    fun `argument extraction parses read path`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockWebSearch)
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractObsidianReadPath")
        }
        method.isAccessible = true

        val path = method.invoke(engine, """{"path":"Work/Specs.md"}""") as String?
        assertEquals("Work/Specs.md", path)

        val nullPath = method.invoke(engine, """{}""") as String?
        assertNull(nullPath)
    }

    @Test
    fun `argument extraction parses write request`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockWebSearch)
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractObsidianWriteRequest")
        }
        method.isAccessible = true

        val req = method.invoke(engine, """{"path":"Work/Specs.md","content":"Hello world","append":true}""")
        requireNotNull(req)
        
        val getPathMethod = req.javaClass.declaredMethods.first { it.name.startsWith("getPath") }
        val getContentMethod = req.javaClass.declaredMethods.first { it.name.startsWith("getContent") }
        val getAppendMethod = req.javaClass.declaredMethods.first { it.name.startsWith("getAppend") }
        
        assertEquals("Work/Specs.md", getPathMethod.invoke(req))
        assertEquals("Hello world", getContentMethod.invoke(req))
        assertEquals(true, getAppendMethod.invoke(req))
    }

    @Test
    fun `argument extraction parses rename request`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockWebSearch)
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractObsidianRenameRequest")
        }
        method.isAccessible = true

        val req = method.invoke(
            engine,
            """{"from_path":"Work/Old.md","to_path":"Work/New.md"}"""
        )
        requireNotNull(req)

        val getFromPathMethod = req.javaClass.declaredMethods.first { it.name.startsWith("getFromPath") }
        val getToPathMethod = req.javaClass.declaredMethods.first { it.name.startsWith("getToPath") }

        assertEquals("Work/Old.md", getFromPathMethod.invoke(req))
        assertEquals("Work/New.md", getToPathMethod.invoke(req))

        val missingReq = method.invoke(engine, """{"from_path":"Work/Old.md"}""")
        assertNull(missingReq)
    }

    @Test
    fun `executeToolCall delegates to obsidian client with all search params`() = runTest {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        coEvery {
            mockObsidian.search("meeting note", "Daily", "modified", "asc")
        } returns Result.success("filtered search result")

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("executeToolCall")
        }
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<String> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<String>) {}
        }

        val res = method.invoke(
            engine,
            OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL,
            """{"query":"meeting note","directory":"Daily","sort_by":"modified","order":"asc"}""",
            continuation
        ) as String
        assertEquals("filtered search result", res)

        coVerify { mockObsidian.search("meeting note", "Daily", "modified", "asc") }
    }

    @Test
    fun `executeToolCall delegates listFolder to obsidian client`() = runTest {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        coEvery { mockObsidian.listFolder("Daily") } returns Result.success("folder listing text")

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("executeToolCall")
        }
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<String> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<String>) {}
        }

        val res = method.invoke(
            engine,
            OpenRouterChatEngine.OBSIDIAN_LIST_FOLDER_TOOL,
            """{"path":"Daily"}""",
            continuation
        ) as String
        assertEquals("folder listing text", res)

        coVerify { mockObsidian.listFolder("Daily") }
    }

    @Test
    fun `executeToolCall delegates to obsidian client for note CRUD`() = runTest {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        coEvery { mockObsidian.search("test query", null, "score", "desc") } returns Result.success("search result text")
        coEvery { mockObsidian.read("test path.md") } returns Result.success("read content text")
        coEvery { mockObsidian.create("test path.md", "initial content") } returns Result.success("create success text")
        coEvery { mockObsidian.update("test path.md", "test content", true) } returns Result.success("update success text")
        coEvery { mockObsidian.rename("test path.md", "renamed path.md") } returns Result.success("rename success text")
        coEvery { mockObsidian.delete("renamed path.md") } returns Result.success("delete success text")

        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("executeToolCall")
        }
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<String> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<String>) {}
        }

        val searchRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_SEARCH_TOOL, """{"query":"test query"}""", continuation) as String
        assertEquals("search result text", searchRes)

        val readRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_READ_TOOL, """{"path":"test path.md"}""", continuation) as String
        assertEquals("read content text", readRes)

        val createRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_CREATE_TOOL, """{"path":"test path.md","content":"initial content"}""", continuation) as String
        assertEquals("create success text", createRes)

        val updateRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_UPDATE_TOOL, """{"path":"test path.md","content":"test content","append":true}""", continuation) as String
        assertEquals("update success text", updateRes)

        val renameRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_RENAME_TOOL, """{"from_path":"test path.md","to_path":"renamed path.md"}""", continuation) as String
        assertEquals("rename success text", renameRes)

        val deleteRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_DELETE_TOOL, """{"path":"renamed path.md"}""", continuation) as String
        assertEquals("delete success text", deleteRes)

        val writeRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL, """{"path":"test path.md","content":"test content","append":true}""", continuation) as String
        assertEquals("update success text", writeRes)

        coVerify { mockObsidian.create("test path.md", "initial content") }
        coVerify(exactly = 2) { mockObsidian.update("test path.md", "test content", true) }
        coVerify { mockObsidian.rename("test path.md", "renamed path.md") }
        coVerify { mockObsidian.delete("renamed path.md") }
    }
}
