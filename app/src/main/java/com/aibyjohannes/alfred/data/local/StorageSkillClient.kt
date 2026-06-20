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
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Skill document exceeds the 128 KiB limit"
        }
        return text
    }

    private fun requireValidSkillId(skillId: String) {
        require(skillId.length <= MAX_SKILL_ID_CHARS && SKILL_ID.matches(skillId)) { "Invalid skill id" }
    }

    companion object {
        private val SKILLS_ROOT = listOf("skills")
        private const val SKILL_FILE = "SKILL.md"
        private const val MAX_DOCUMENT_BYTES = 128 * 1024
        private const val MAX_DESCRIPTION_CHARS = 1024
        private const val MAX_SKILL_ID_CHARS = 64
        private const val MAX_SKILLS = 100
        private val SKILL_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val ALLOWED_REFERENCE_EXTENSIONS = setOf("md", "txt")
        private val yaml = Load(LoadSettings.builder().build())
    }
}
