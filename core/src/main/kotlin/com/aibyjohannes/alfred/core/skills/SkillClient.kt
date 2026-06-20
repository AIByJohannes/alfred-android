package com.aibyjohannes.alfred.core.skills

data class SkillSummary(
    val id: String,
    val name: String,
    val description: String
)

interface SkillClient {
    suspend fun listSkills(): Result<List<SkillSummary>>
    suspend fun readSkill(skillId: String): Result<String>
    suspend fun readReference(skillId: String, path: String): Result<String>
}
