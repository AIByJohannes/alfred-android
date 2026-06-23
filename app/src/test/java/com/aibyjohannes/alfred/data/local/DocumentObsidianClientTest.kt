package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentObsidianClientTest {
    @Test
    fun `note operations reject unsafe note paths before touching storage`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = DocumentObsidianClient(context, Uri.parse("content://vault/root"))

        val invalidPaths = listOf(
            "",
            "/absolute.md",
            "C:/absolute.md",
            "Folder\\Note.md",
            "../escape.md",
            "Folder/../escape.md",
            ".obsidian/config.md",
            "Folder/",
            "Folder/Note.txt"
        )

        invalidPaths.forEach { path ->
            assertTrue("read should reject $path", client.read(path).isFailure)
            assertTrue("create should reject $path", client.create(path, "content").isFailure)
            assertTrue("update should reject $path", client.update(path, "content", append = false).isFailure)
            assertTrue("delete should reject $path", client.delete(path).isFailure)
        }
    }
}
