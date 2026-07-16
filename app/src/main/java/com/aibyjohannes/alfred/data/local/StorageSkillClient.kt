package com.aibyjohannes.alfred.data.local

import com.aibyjohannes.alfred.core.skills.SkillClient
import com.aibyjohannes.alfred.core.skills.SkillSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class StorageSkillClient(
    private val storage: ChatHistoryStorage
) : SkillClient {
    override suspend fun listSkills(): Result<List<SkillSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            storage.ensureDirectory(SKILLS_ROOT)
            storage.listChildren(SKILLS_ROOT)
                .asSequence()
                .filter { it.isDirectory && SKILL_ID.matches(it.name) }
                .mapNotNull { parseSummary(it.name) }
                .sortedBy { it.id }
                .take(MAX_SKILLS)
                .toList()
        }
    }

    override suspend fun readSkill(skillId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            val summary = parseSummary(skillId)
                ?: throw IllegalArgumentException("Skill is missing or invalid: $skillId")
            require(summary.id == skillId)
            readBounded(SKILLS_ROOT + listOf(skillId, SKILL_FILE))
        }
    }

    override suspend fun readReference(skillId: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            if (parseSummary(skillId) == null) {
                throw IllegalArgumentException("Skill is missing or invalid: $skillId")
            }
            val segments = validateReferencePath(path)
            readBounded(SKILLS_ROOT + skillId + segments)
        }
    }

    override suspend fun createSkill(
        skillId: String,
        description: String,
        instructions: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            val normalizedDescription = requireValidDescription(description)
            requireDocumentWithinLimit(instructions)
            storage.ensureDirectory(SKILLS_ROOT)
            if (skillDirectoryExists(skillId)) {
                throw IllegalArgumentException("Skill already exists: $skillId")
            }
            val skillText = buildSkillDocument(skillId, normalizedDescription, instructions)
            requireDocumentWithinLimit(skillText)
            val skillPath = SKILLS_ROOT + listOf(skillId, SKILL_FILE)
            storage.createFileExclusive(skillPath, MARKDOWN_MIME_TYPE)
            storage.writeText(skillPath, skillText, MARKDOWN_MIME_TYPE)
            if (parseSummary(skillId) == null) {
                throw IllegalStateException("Created skill is invalid: $skillId")
            }
            "Created skill: $skillId"
        }
    }

    override suspend fun renameSkill(fromSkillId: String, toSkillId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(fromSkillId)
            requireValidSkillId(toSkillId)
            require(fromSkillId != toSkillId) { "Source and destination skill ids must differ" }
            val summary = parseSummary(fromSkillId)
                ?: throw IllegalArgumentException("Skill is missing or invalid: $fromSkillId")
            if (skillDirectoryExists(toSkillId)) {
                throw IllegalArgumentException("Skill already exists: $toSkillId")
            }
            val skillText = readBounded(SKILLS_ROOT + listOf(fromSkillId, SKILL_FILE))
            val updatedSkillText = rewriteSkillName(skillText, toSkillId, summary.description)
            requireDocumentWithinLimit(updatedSkillText)
            storage.renameDirectory(SKILLS_ROOT + fromSkillId, toSkillId)
            storage.replaceTextVerified(SKILLS_ROOT + listOf(toSkillId, SKILL_FILE), updatedSkillText, MARKDOWN_MIME_TYPE)
            if (parseSummary(toSkillId) == null) {
                throw IllegalStateException("Renamed skill is invalid: $toSkillId")
            }
            "Renamed skill from $fromSkillId to $toSkillId"
        }
    }

    override suspend fun writeReference(
        skillId: String,
        path: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            if (parseSummary(skillId) == null) {
                throw IllegalArgumentException("Skill is missing or invalid: $skillId")
            }
            requireDocumentWithinLimit(content)
            val segments = validateReferencePath(path)
            require(segments != listOf(SKILL_FILE)) { "Use the skill lifecycle tools to manage SKILL.md" }
            storage.writeText(SKILLS_ROOT + skillId + segments, content, referenceMimeType(segments.last()))
            "Wrote skill reference: $skillId/${segments.joinToString("/")}"
        }
    }

    override suspend fun deleteReference(skillId: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            require(parseSummary(skillId) != null) { "Skill is missing or invalid: $skillId" }
            val segments = validateReferencePath(path)
            require(segments != listOf(SKILL_FILE)) { "SKILL.md is managed by the skill lifecycle tools" }
            storage.delete(SKILLS_ROOT + skillId + segments)
            "Deleted skill reference: $skillId/${segments.joinToString("/")}"
        }
    }

    override suspend fun moveReference(skillId: String, fromPath: String, toPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireValidSkillId(skillId)
            require(parseSummary(skillId) != null) { "Skill is missing or invalid: $skillId" }
            val from = validateReferencePath(fromPath)
            val to = validateReferencePath(toPath)
            require(from != listOf(SKILL_FILE) && to != listOf(SKILL_FILE)) { "SKILL.md is managed by the skill lifecycle tools" }
            val content = readBounded(SKILLS_ROOT + skillId + from)
            storage.writeText(SKILLS_ROOT + skillId + to, content, referenceMimeType(to.last()))
            storage.delete(SKILLS_ROOT + skillId + from)
            "Moved skill reference: $skillId/${from.joinToString("/")} -> ${to.joinToString("/")}"
        }
    }

    private fun parseSummary(skillId: String): SkillSummary? {
        val text = when (val result = storage.readText(SKILLS_ROOT + listOf(skillId, SKILL_FILE))) {
            is StorageReadResult.Found -> result.text
            StorageReadResult.Missing -> return null
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return null
        val frontmatter = extractFrontmatter(text) ?: return null
        val loaded = runCatching { yaml.loadFromString(frontmatter) }.getOrNull() as? Map<*, *> ?: return null
        val name = loaded["name"]?.toString()?.trim().orEmpty()
        val description = loaded["description"]?.toString()?.trim().orEmpty()
        if (
            name != skillId ||
            name.length > MAX_SKILL_ID_CHARS ||
            !SKILL_ID.matches(name) ||
            description.isBlank() ||
            description.length > MAX_DESCRIPTION_CHARS
        ) return null
        return SkillSummary(id = skillId, name = name, description = description)
    }

    private fun skillDirectoryExists(skillId: String): Boolean {
        return storage.listChildren(SKILLS_ROOT).any { it.isDirectory && it.name == skillId }
    }

    private fun buildSkillDocument(skillId: String, description: String, instructions: String): String {
        return buildString {
            appendLine("---")
            appendLine("name: $skillId")
            appendLine("description: |-")
            description.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
                appendLine("  $line")
            }
            appendLine("---")
            append(instructions.replace("\r\n", "\n").replace('\r', '\n'))
        }
    }

    private fun rewriteSkillName(text: String, newSkillId: String, description: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        require(normalized.startsWith("---\n")) { "Skill is missing frontmatter" }
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        require(end >= 0) { "Skill is missing closing frontmatter" }
        val frontmatter = normalized.substring(4, end)
        val body = normalized.substring(end + "\n---\n".length)
        var replaced = false
        val updatedFrontmatter = frontmatter.lines().joinToString("\n") { line ->
            if (!replaced && line.trimStart().startsWith("name:")) {
                replaced = true
                "name: $newSkillId"
            } else {
                line
            }
        }
        return if (replaced) {
            "---\n$updatedFrontmatter\n---\n$body"
        } else {
            buildSkillDocument(newSkillId, description, body)
        }
    }

    private fun extractFrontmatter(text: String): String? {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        if (end < 0) return null
        return normalized.substring(4, end)
    }

    private fun validateReferencePath(path: String): List<String> {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty()) { "Reference path is required" }
        require(!trimmed.startsWith('/') && !trimmed.startsWith('\\')) { "Absolute paths are not allowed" }
        require('\\' !in trimmed) { "Reference paths must use forward slashes" }
        val segments = trimmed.split('/')
        require(segments.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Reference path must stay inside the skill directory"
        }
        val extension = segments.last().substringAfterLast('.', missingDelimiterValue = "").lowercase()
        require(extension in ALLOWED_REFERENCE_EXTENSIONS) { "Only Markdown and text references are supported" }
        return segments
    }

    private fun readBounded(path: List<String>): String {
        val text = when (val result = storage.readText(path)) {
            is StorageReadResult.Found -> result.text
            StorageReadResult.Missing -> throw IllegalArgumentException("File not found: ${path.joinToString("/")}")
        }
        requireDocumentWithinLimit(text)
        return text
    }

    private fun requireDocumentWithinLimit(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Skill document exceeds the 128 KiB limit"
        }
    }

    private fun requireValidDescription(description: String): String {
        val trimmed = description.trim()
        require(trimmed.isNotEmpty()) { "Skill description is required" }
        require(trimmed.length <= MAX_DESCRIPTION_CHARS) { "Skill description exceeds the 1024 character limit" }
        return trimmed
    }

    private fun requireValidSkillId(skillId: String) {
        require(skillId.length <= MAX_SKILL_ID_CHARS && SKILL_ID.matches(skillId)) { "Invalid skill id" }
    }

    private fun referenceMimeType(fileName: String): String {
        return if (fileName.endsWith(".md", ignoreCase = true)) MARKDOWN_MIME_TYPE else "text/plain"
    }

    companion object {
        private val SKILLS_ROOT = listOf("skills")
        private const val SKILL_FILE = "SKILL.md"
        private const val MARKDOWN_MIME_TYPE = "text/markdown"
        private const val MAX_DOCUMENT_BYTES = 128 * 1024
        private const val MAX_DESCRIPTION_CHARS = 1024
        private const val MAX_SKILL_ID_CHARS = 64
        private const val MAX_SKILLS = 100
        private val SKILL_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val ALLOWED_REFERENCE_EXTENSIONS = setOf("md", "txt")
        private val yaml = Load(LoadSettings.builder().build())
    }
}
