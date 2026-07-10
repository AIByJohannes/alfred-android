package com.aibyjohannes.alfred.core.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianCliEmulatorTest {
    @Test fun `quoted append delegates to vault client`() = runTest {
        var path = ""; var content = ""
        val emulator = ObsidianCliEmulator(fakeClient { p, c, append -> path = p; content = c; Result.success(if (append) "appended" else "written") })
        assertEquals("appended", emulator.execute("obsidian append path=Daily/2026-07-10.md content=\"- [ ] Call bank tomorrow\"").getOrThrow())
        assertEquals("Daily/2026-07-10.md", path); assertEquals("- [ ] Call bank tomorrow", content)
    }
    @Test fun `shell syntax and unknown commands are rejected`() = runTest {
        val emulator = ObsidianCliEmulator(fakeClient { _, _, _ -> Result.success("unused") })
        assertTrue(emulator.execute("ls | rm -rf /").isFailure)
        assertTrue(emulator.execute("obsidian eval code=bad").isFailure)
    }
    private fun fakeClient(update: suspend (String, String, Boolean) -> Result<String>) = object : ObsidianClient {
        override suspend fun search(query: String, directory: String?, sortBy: String, order: String) = Result.success("")
        override suspend fun listFolder(path: String) = Result.success("")
        override suspend fun read(path: String) = Result.success("")
        override suspend fun create(path: String, content: String) = Result.success("")
        override suspend fun update(path: String, content: String, append: Boolean) = update(path, content, append)
        override suspend fun rename(fromPath: String, toPath: String) = Result.success("")
        override suspend fun delete(path: String) = Result.success("")
    }
}
