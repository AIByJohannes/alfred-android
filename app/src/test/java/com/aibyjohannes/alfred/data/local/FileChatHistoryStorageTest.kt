package com.aibyjohannes.alfred.data.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileChatHistoryStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ensureReady creates root directory`() {
        val rootDir = File(temporaryFolder.newFolder(), "new_root")
        assertFalse(rootDir.exists())

        val storage = FileChatHistoryStorage(rootDir)
        storage.ensureReady()

        assertTrue(rootDir.exists())
        assertTrue(rootDir.isDirectory)
    }

    @Test
    fun `ensureDirectory creates subdirectories`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)

        val path = listOf("sub", "nested")
        storage.ensureDirectory(path)

        val expectedDir = File(File(rootDir, "sub"), "nested")
        assertTrue(expectedDir.exists())
        assertTrue(expectedDir.isDirectory)
    }

    @Test
    fun `readText returns Found when file exists and Missing when not`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val path = listOf("test.txt")

        // Initial state: missing
        val missingResult = storage.readText(path)
        assertTrue(missingResult is StorageReadResult.Missing)

        // Write text and read
        storage.writeText(path, "hello world")
        val foundResult = storage.readText(path)
        assertTrue(foundResult is StorageReadResult.Found)
        assertEquals("hello world", (foundResult as StorageReadResult.Found).text)
    }

    @Test
    fun `listChildren lists files and directories correctly`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)

        // List empty root
        val emptyList = storage.listChildren(emptyList())
        assertTrue(emptyList.isEmpty())

        // Add some files/dirs
        storage.writeText(listOf("file1.txt"), "content")
        storage.ensureDirectory(listOf("dir1"))
        storage.writeText(listOf("dir1", "file2.txt"), "more content")

        // List root
        val rootChildren = storage.listChildren(emptyList())
        assertEquals(2, rootChildren.size)
        val file1Entry = rootChildren.first { it.name == "file1.txt" }
        assertFalse(file1Entry.isDirectory)
        assertEquals("content".length.toLong(), file1Entry.size)

        val dir1Entry = rootChildren.first { it.name == "dir1" }
        assertTrue(dir1Entry.isDirectory)
        assertEquals(0L, dir1Entry.size)

        // List subdirectory
        val subChildren = storage.listChildren(listOf("dir1"))
        assertEquals(1, subChildren.size)
        assertEquals("file2.txt", subChildren.first().name)
        assertFalse(subChildren.first().isDirectory)
    }

    @Test(expected = IllegalStateException::class)
    fun `listChildren throws exception if path is not a directory`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val path = listOf("file.txt")
        storage.writeText(path, "content")

        storage.listChildren(path)
    }

    @Test
    fun `listChildren returns empty list if directory does not exist`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val children = storage.listChildren(listOf("nonexistent"))
        assertTrue(children.isEmpty())
    }

    @Test
    fun `createFileExclusive creates file if not exists and throws if does`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val path = listOf("exclusive.txt")

        storage.createFileExclusive(path)
        val file = File(rootDir, "exclusive.txt")
        assertTrue(file.exists())
        assertTrue(file.isFile)

        // Try creating again, should throw IllegalStateException
        val result = runCatching {
            storage.createFileExclusive(path)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `writeText writes text and handles nested paths`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val path = listOf("nested", "sub", "file.txt")

        storage.writeText(path, "my data")
        val file = File(rootDir, "nested/sub/file.txt")
        assertTrue(file.exists())
        assertEquals("my data", file.readText())
    }

    @Test
    fun `appendLine appends text with newline`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        val path = listOf("append.txt")

        storage.appendLine(path, "line 1")
        storage.appendLine(path, "line 2")

        val file = File(rootDir, "append.txt")
        assertEquals("line 1\nline 2\n", file.readText())
    }

    @Test
    fun `delete removes files and directories`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)

        val filePath = listOf("to_delete.txt")
        storage.writeText(filePath, "data")
        assertTrue(File(rootDir, "to_delete.txt").exists())

        storage.delete(filePath)
        assertFalse(File(rootDir, "to_delete.txt").exists())

        // Nonexistent delete shouldn't throw
        storage.delete(listOf("nonexistent.txt"))

        // Delete directory recursively
        val dirPath = listOf("dir_to_delete")
        storage.ensureDirectory(dirPath)
        storage.writeText(listOf("dir_to_delete", "file.txt"), "data")
        assertTrue(File(rootDir, "dir_to_delete").exists())

        storage.delete(dirPath)
        assertFalse(File(rootDir, "dir_to_delete").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty path throws IllegalArgumentException`() {
        val rootDir = temporaryFolder.newFolder()
        val storage = FileChatHistoryStorage(rootDir)
        storage.writeText(emptyList(), "content")
    }
}
