package com.aibyjohannes.alfred.core.engine

import com.aibyjohannes.alfred.core.search.ObsidianClient
import com.aibyjohannes.alfred.core.search.WebSearchClient
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        assertTrue(names.contains(OpenRouterChatEngine.OBSIDIAN_READ_TOOL))
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
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_READ_TOOL))
        assertTrue(!names.contains(OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL))
    }

    @Test
    fun `argument extraction parses search query`() {
        val engine = OpenRouterChatEngine(apiKey = "test", webSearchClient = mockWebSearch)
        val method = OpenRouterChatEngine::class.java.declaredMethods.first {
            it.name.startsWith("extractObsidianSearchQuery")
        }
        method.isAccessible = true

        val query = method.invoke(engine, """{"query":"meeting note"}""") as String?
        assertEquals("meeting note", query)

        val nullQuery = method.invoke(engine, """{}""") as String?
        assertNull(nullQuery)
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
    fun `executeToolCall delegates to obsidian client`() = runTest {
        val mockObsidian = mockk<ObsidianClient>()
        val engine = OpenRouterChatEngine(
            apiKey = "test",
            webSearchClient = mockWebSearch,
            obsidianClient = mockObsidian
        )

        coEvery { mockObsidian.search("test query") } returns Result.success("search result text")
        coEvery { mockObsidian.read("test path") } returns Result.success("read content text")
        coEvery { mockObsidian.write("test path", "test content", true) } returns Result.success("write success text")

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

        val readRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_READ_TOOL, """{"path":"test path"}""", continuation) as String
        assertEquals("read content text", readRes)

        val writeRes = method.invoke(engine, OpenRouterChatEngine.OBSIDIAN_WRITE_TOOL, """{"path":"test path","content":"test content","append":true}""", continuation) as String
        assertEquals("write success text", writeRes)
    }
}
