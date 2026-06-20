package com.aibyjohannes.alfred.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StorageSkillClientTest {
    @Test
    fun `discovers valid skills and ignores malformed entries`() = runTest {
        val root = Files.createTempDirectory("alfred-skills").toFile()
        val storage = FileChatHistoryStorage(root)
        storage.writeText(
            listOf("skills", "meeting-prep", "SKILL.md"),
            "---\nname: meeting-prep\ndescription: Prepare for meetings\n---\n\nFollow the agenda."
        )
        storage.writeText(
            listOf("skills", "wrong-name", "SKILL.md"),
            "---\nname: another-name\ndescription: Invalid\n---\n"
        )
        storage.writeText(listOf("skills", "missing-frontmatter", "SKILL.md"), "Instructions only")

        val result = StorageSkillClient(storage).listSkills().getOrThrow()

        assertEquals(listOf("meeting-prep"), result.map { it.id })
        assertEquals("Prepare for meetings", result.single().description)
    }

    @Test
    fun `refreshes externally edited skill metadata`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-refresh").toFile()
        val storage = FileChatHistoryStorage(root)
        val path = listOf("skills", "writer", "SKILL.md")
        storage.writeText(path, "---\nname: writer\ndescription: First description\n---\nBody")
        val client = StorageSkillClient(storage)
        assertEquals("First description", client.listSkills().getOrThrow().single().description)

        storage.writeText(path, "---\nname: writer\ndescription: Updated description\n---\nBody")

        assertEquals("Updated description", client.listSkills().getOrThrow().single().description)
    }

    @Test
    fun `reads skill and nested text reference`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-read").toFile()
        val storage = FileChatHistoryStorage(root)
        val skill = "---\nname: researcher\ndescription: Research topics\n---\nRead references/source.md"
        storage.writeText(listOf("skills", "researcher", "SKILL.md"), skill)
        storage.writeText(listOf("skills", "researcher", "references", "source.md"), "Source details")
        val client = StorageSkillClient(storage)

        assertEquals(skill, client.readSkill("researcher").getOrThrow())
        assertEquals("Source details", client.readReference("researcher", "references/source.md").getOrThrow())
    }

    @Test
    fun `rejects traversal unsupported files and oversized documents`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-safe").toFile()
        val storage = FileChatHistoryStorage(root)
        storage.writeText(
            listOf("skills", "safe-skill", "SKILL.md"),
            "---\nname: safe-skill\ndescription: Safe skill\n---\nBody"
        )
        storage.writeText(listOf("skills", "safe-skill", "large.txt"), "x".repeat(128 * 1024 + 1))
        val client = StorageSkillClient(storage)

        assertTrue(client.readReference("safe-skill", "../secret.md").isFailure)
        assertTrue(client.readReference("safe-skill", "references/script.sh").isFailure)
        assertTrue(client.readReference("safe-skill", "large.txt").isFailure)
    }

    @Test
    fun `missing skills directory is created and returns empty catalog`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-empty").toFile()

        val result = StorageSkillClient(FileChatHistoryStorage(root)).listSkills().getOrThrow()

        assertTrue(result.isEmpty())
        assertTrue(root.resolve("skills").isDirectory)
    }
}
