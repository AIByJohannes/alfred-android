package com.aibyjohannes.alfred.evals

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalCase(
    val id: String,
    val userInput: String,
    val history: List<EvalMessage> = emptyList(),
    val checks: EvalChecks = EvalChecks(),
    val gradingNotes: List<String> = emptyList(),
    val judgeRubricOverride: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalMessage(
    val role: String,
    val content: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalChecks(
    val mustInclude: List<String> = emptyList(),
    val mustNotInclude: List<String> = emptyList(),
    val requireToolCall: Boolean = false,
    val forbidToolAbsenceClaim: Boolean = false,
    val mustMatchRegex: List<String> = emptyList()
)

data class CaseResult(
    val id: String,
    val passed: Boolean,
    val failures: List<String>,
    val response: String?,
    val toolCallNames: List<String>,
    val error: String?,
    val judgeResult: JudgeResult?
)

data class SuiteResult(
    val suite: String,
    val strict: Boolean,
    val totalCases: Int,
    val passedCases: Int,
    val score: Double,
    val skipped: Boolean,
    val skipReason: String?,
    val results: List<CaseResult>,
    val judgeSummary: JudgeSummary?
)

data class JudgeConfig(
    val enabled: Boolean,
    val model: String,
    val minAverageScore: Double,
    val temperature: Double
)

data class JudgeResult(
    val factuality: Int?,
    val relevance: Int?,
    val completeness: Int?,
    val instructionFollowing: Int?,
    val overallScore: Double?,
    val verdict: String?,
    val rationale: String?,
    val error: String?,
    val rawResponse: String?
)

data class JudgeSummary(
    val enabled: Boolean,
    val model: String,
    val totalJudged: Int,
    val averageOverallScore: Double?,
    val averageFactuality: Double?,
    val averageRelevance: Double?,
    val averageCompleteness: Double?,
    val averageInstructionFollowing: Double?,
    val passRate: Double?,
    val minAverageScore: Double,
    val thresholdPassed: Boolean?
)
