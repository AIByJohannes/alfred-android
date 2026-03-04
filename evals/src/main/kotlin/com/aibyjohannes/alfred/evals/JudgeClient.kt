package com.aibyjohannes.alfred.evals

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JudgeClient(
    private val apiKey: String,
    private val config: JudgeConfig
) {
    private fun buildClient(): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl("https://openrouter.ai/api/v1")
            .build()
    }

    suspend fun judge(
        evalCase: EvalCase,
        candidateResponse: String,
        toolCallNames: List<String>,
        mapper: com.fasterxml.jackson.databind.ObjectMapper
    ): JudgeResult {
        return try {
            val client = buildClient()
            val prompt = buildJudgePrompt(evalCase, candidateResponse, toolCallNames)
            val messages = listOf(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(
                            "You are a strict evaluator. Output ONLY valid JSON matching the requested schema. " +
                                "Do not add markdown. Do not reward verbosity."
                        )
                        .build()
                ),
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(prompt)
                        .build()
                )
            )

            val params = ChatCompletionCreateParams.builder()
                .model(config.model)
                .messages(messages)
                .build()

            val response = withContext(Dispatchers.IO) {
                client.chat().completions().create(params)
            }
            val raw = response.choices().firstOrNull()?.message()?.content()?.orElse(null)
                ?: return JudgeResult(
                    factuality = null,
                    relevance = null,
                    completeness = null,
                    instructionFollowing = null,
                    overallScore = null,
                    verdict = null,
                    rationale = null,
                    error = "Empty judge response",
                    rawResponse = null
                )

            val json = extractJsonObject(raw)
            val parsed = mapper.readValue(json, JudgeSchema::class.java)
            val overall = parsed.overallScore
                ?: listOfNotNull(parsed.factuality, parsed.relevance, parsed.completeness, parsed.instructionFollowing)
                    .takeIf { it.isNotEmpty() }
                    ?.average()

            JudgeResult(
                factuality = parsed.factuality,
                relevance = parsed.relevance,
                completeness = parsed.completeness,
                instructionFollowing = parsed.instructionFollowing,
                overallScore = overall,
                verdict = parsed.verdict?.lowercase(),
                rationale = parsed.rationale?.trim()?.take(600),
                error = null,
                rawResponse = raw
            )
        } catch (e: Exception) {
            JudgeResult(
                factuality = null,
                relevance = null,
                completeness = null,
                instructionFollowing = null,
                overallScore = null,
                verdict = null,
                rationale = null,
                error = "Judge failed: ${e.message}",
                rawResponse = null
            )
        }
    }

    private fun buildJudgePrompt(
        evalCase: EvalCase,
        candidateResponse: String,
        toolCallNames: List<String>
    ): String {
        val rubric = evalCase.judgeRubricOverride ?: DEFAULT_RUBRIC
        val notes = if (evalCase.gradingNotes.isEmpty()) {
            "No additional grading notes."
        } else {
            evalCase.gradingNotes.joinToString(separator = "\n") { "- $it" }
        }
        val history = if (evalCase.history.isEmpty()) {
            "No prior conversation."
        } else {
            evalCase.history.joinToString(separator = "\n") { "${it.role}: ${it.content}" }
        }
        val tools = if (toolCallNames.isEmpty()) "none" else toolCallNames.joinToString(", ")

        return """
Evaluate this assistant response using the rubric below.

User input:
${evalCase.userInput}

Conversation history:
$history

Assistant response to evaluate:
$candidateResponse

Tool calls made by assistant:
$tools

Grading notes:
$notes

Rubric:
$rubric

Return JSON only with this exact schema:
{
  "factuality": 1-5,
  "relevance": 1-5,
  "completeness": 1-5,
  "instruction_following": 1-5,
  "overall_score": 1-5,
  "verdict": "pass|fail",
  "rationale": "max 60 words"
}
""".trim()
    }

    private fun extractJsonObject(raw: String): String {
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first == -1 || last == -1 || last <= first) {
            throw IllegalArgumentException("No JSON object found in judge output")
        }
        return raw.substring(first, last + 1)
    }

    private data class JudgeSchema(
        val factuality: Int? = null,
        val relevance: Int? = null,
        val completeness: Int? = null,
        val instructionFollowing: Int? = null,
        val overallScore: Double? = null,
        val verdict: String? = null,
        val rationale: String? = null
    )

    companion object {
        const val DEFAULT_MODEL = "deepseek/deepseek-v3.2"

        const val DEFAULT_RUBRIC = """
- factuality (1-5): factual correctness and avoidance of fabricated claims.
- relevance (1-5): directly addresses the user request.
- completeness (1-5): covers key points expected for the request.
- instruction_following (1-5): respects explicit instructions and constraints.
- overall_score (1-5): overall quality, not a verbosity reward.
- verdict: "pass" if overall_score >= 4 and no severe factual or instruction-following issue, otherwise "fail".
"""
    }
}
