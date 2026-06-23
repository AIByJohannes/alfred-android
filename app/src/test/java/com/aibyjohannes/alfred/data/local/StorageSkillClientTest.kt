package com.aibyjohannes.alfred.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    @Test
    fun `creates valid skill and lists it immediately`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-create").toFile()
        val client = StorageSkillClient(FileChatHistoryStorage(root))

        val result = client.createSkill(
            skillId = "writing-docs",
            description = "Write project documentation",
            instructions = "# Writing Docs\n\nFollow the local style."
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("writing-docs"), client.listSkills().getOrThrow().map { it.id })
        val skill = client.readSkill("writing-docs").getOrThrow()
        assertTrue(skill.contains("name: writing-docs"))
        assertTrue(skill.contains("description: |-"))
        assertTrue(skill.contains("# Writing Docs"))
    }

    @Test
    fun `createSkill rejects duplicate invalid empty description and oversized skills`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-create-invalid").toFile()
        val client = StorageSkillClient(FileChatHistoryStorage(root))
        assertTrue(client.createSkill("valid-skill", "Valid description", "Body").isSuccess)

        assertTrue(client.createSkill("valid-skill", "Duplicate", "Body").isFailure)
        assertTrue(client.createSkill("InvalidSkill", "Invalid id", "Body").isFailure)
        assertTrue(client.createSkill("empty-description", "   ", "Body").isFailure)
        assertTrue(client.createSkill("large-skill", "Large", "x".repeat(128 * 1024 + 1)).isFailure)
    }

    @Test
    fun `renames skill updates frontmatter and preserves references`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-rename").toFile()
        val storage = FileChatHistoryStorage(root)
        val client = StorageSkillClient(storage)
        client.createSkill("old-skill", "Old description", "Read references/guide.md").getOrThrow()
        client.writeReference("old-skill", "references/guide.md", "Reference text").getOrThrow()

        val result = client.renameSkill("old-skill", "new-skill")

        assertTrue(result.isSuccess)
        assertFalse(File(root, "skills/old-skill").exists())
        assertEquals(listOf("new-skill"), client.listSkills().getOrThrow().map { it.id })
        assertTrue(client.readSkill("new-skill").getOrThrow().contains("name: new-skill"))
        assertEquals("Reference text", client.readReference("new-skill", "references/guide.md").getOrThrow())
    }

    @Test
    fun `renameSkill rejects missing source and destination collisions`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-rename-invalid").toFile()
        val client = StorageSkillClient(FileChatHistoryStorage(root))
        client.createSkill("source-skill", "Source", "Body").getOrThrow()
        client.createSkill("target-skill", "Target", "Body").getOrThrow()

        assertTrue(client.renameSkill("missing-skill", "new-skill").isFailure)
        assertTrue(client.renameSkill("source-skill", "target-skill").isFailure)
        assertTrue(client.renameSkill("source-skill", "InvalidSkill").isFailure)
    }

    @Test
    fun `writes valid references and rejects unsafe writes`() = runTest {
        val root = Files.createTempDirectory("alfred-skills-reference-write").toFile()
        val client = StorageSkillClient(FileChatHistoryStorage(root))
        client.createSkill("safe-skill", "Safe skill", "Read references/guide.md").getOrThrow()

        assertTrue(client.writeReference("safe-skill", "references/guide.md", "Guide").isSuccess)
        assertTrue(client.writeReference("safe-skill", "notes.txt", "Notes").isSuccess)
        assertEquals("Guide", client.readReference("safe-skill", "references/guide.md").getOrThrow())
        assertEquals("Notes", client.readReference("safe-skill", "notes.txt").getOrThrow())

        assertTrue(client.writeReference("safe-skill", "../secret.md", "No").isFailure)
        assertTrue(client.writeReference("safe-skill", "/absolute.md", "No").isFailure)
        assertTrue(client.writeReference("safe-skill", "references\\guide.md", "No").isFailure)
        assertTrue(client.writeReference("safe-skill", "references/script.sh", "No").isFailure)
        assertTrue(client.writeReference("safe-skill", "SKILL.md", "No").isFailure)
        assertTrue(client.writeReference("safe-skill", "large.txt", "x".repeat(128 * 1024 + 1)).isFailure)
    }
}
