package com.aibyjohannes.alfred.evals

import com.aibyjohannes.alfred.core.engine.OpenRouterChatEngine
import com.aibyjohannes.alfred.core.model.CoreChatMessage
import com.aibyjohannes.alfred.core.search.PerplexitySearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.math.roundToInt

private val mapper = ObjectMapper().apply {
    findAndRegisterModules()
    propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    enable(SerializationFeature.INDENT_OUTPUT)
}

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val suite = options["suite"] ?: "smoke"
    val datasetPath = options["dataset"] ?: "datasets/smoke.jsonl"
    val strict = options.containsKey("strict")
    val judgeConfig = resolveJudgeConfig(suite, options)
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
    val outputDir = Path.of(options["output"] ?: "reports/$suite-$timestamp")
    Files.createDirectories(outputDir)
    Files.createDirectories(outputDir.resolve("traces"))

    val apiKey = resolveApiKey()
    if (apiKey.isNullOrBlank()) {
        val skipped = SuiteResult(
            suite = suite,
            strict = strict,
            totalCases = 0,
            passedCases = 0,
            score = 0.0,
            skipped = true,
            skipReason = "OPENROUTER_API_KEY missing; eval run skipped.",
            results = emptyList(),
            judgeSummary = null
        )
        writeSuiteArtifacts(skipped, outputDir)
        if (strict) {
            System.err.println("Strict eval mode: missing OPENROUTER_API_KEY")
            kotlin.system.exitProcess(1)
        }
        return
    }

    val dataset = loadDataset(Path.of(datasetPath))
    val judgeClient = if (judgeConfig.enabled) JudgeClient(apiKey = apiKey, config = judgeConfig) else null
    val engine = OpenRouterChatEngine(
        apiKey = apiKey,
        model = System.getenv("EVAL_MODEL_MAIN") ?: OpenRouterChatEngine.DEFAULT_MODEL,
        webSearchClient = PerplexitySearchClient(
            apiKey = apiKey,
            model = System.getenv("EVAL_MODEL_SEARCH") ?: PerplexitySearchClient.DEFAULT_MODEL
        )
    )

    val results = dataset.map { evalCase ->
        val history = evalCase.history.map { CoreChatMessage(role = it.role, content = it.content) }
        val turn = runBlocking { engine.sendMessage(evalCase.userInput, history) }
        if (turn.isFailure) {
            CaseResult(
                id = evalCase.id,
                passed = false,
                failures = listOf("Engine call failed"),
                response = null,
                toolCallNames = emptyList(),
                error = turn.exceptionOrNull()?.message,
                judgeResult = null
            )
        } else {
            val data = turn.getOrThrow()
            val failures = validateCase(evalCase, data.content, data.toolCalls.map { it.name })
            val judgeResult = if (judgeClient != null) {
                runBlocking {
                    judgeClient.judge(
                        evalCase = evalCase,
                        candidateResponse = data.content,
                        toolCallNames = data.toolCalls.map { it.name },
                        mapper = mapper
                    )
                }
            } else {
                null
            }
            val caseResult = CaseResult(
                id = evalCase.id,
                passed = failures.isEmpty(),
                failures = failures,
                response = data.content,
                toolCallNames = data.toolCalls.map { it.name },
                error = null,
                judgeResult = judgeResult
            )
            mapper.writeValue(
                outputDir.resolve("traces").resolve("${evalCase.id}.json").toFile(),
                mapOf(
                    "id" to evalCase.id,
                    "input" to evalCase.userInput,
                    "history" to evalCase.history,
                    "tool_calls" to data.toolCalls,
                    "response" to data.content,
                    "failures" to failures,
                    "judge_result" to judgeResult
                )
            )
            caseResult
        }
    }

    val passedCases = results.count { it.passed }
    val score = if (results.isEmpty()) 0.0 else passedCases.toDouble() / results.size.toDouble()
    val judgeSummary = buildJudgeSummary(judgeConfig, results)
    val suiteResult = SuiteResult(
        suite = suite,
        strict = strict,
        totalCases = results.size,
        passedCases = passedCases,
        score = score,
        skipped = false,
        skipReason = null,
        results = results,
        judgeSummary = judgeSummary
    )
    writeSuiteArtifacts(suiteResult, outputDir)

    val percentage = (score * 1000.0).roundToInt() / 10.0
    println("Eval suite '$suite': $passedCases/${results.size} passed ($percentage%)")
    if (judgeSummary?.enabled == true) {
        val judgePct = judgeSummary.averageOverallScore?.let { (it * 20.0 * 10.0).roundToInt() / 10.0 }
        println("Judge (${judgeSummary.model}) avg overall: ${judgePct ?: "n/a"}%")
    }
    println("Report: ${outputDir.toAbsolutePath()}")

    val deterministicFailed = results.any { !it.passed }
    val judgeThresholdFailed = judgeSummary?.thresholdPassed == false
    if (strict && (deterministicFailed || judgeThresholdFailed)) {
        kotlin.system.exitProcess(1)
    }
}

private fun validateCase(evalCase: EvalCase, response: String, toolCalls: List<String>): List<String> {
    val failures = mutableListOf<String>()
    val responseLower = response.lowercase()

    for (required in evalCase.checks.mustInclude) {
        if (!responseLower.contains(required.lowercase())) {
            failures.add("Missing required phrase: '$required'")
        }
    }

    for (forbidden in evalCase.checks.mustNotInclude) {
        if (responseLower.contains(forbidden.lowercase())) {
            failures.add("Contains forbidden phrase: '$forbidden'")
        }
    }

    if (evalCase.checks.requireToolCall && toolCalls.none { it == OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME }) {
        failures.add("Required tool call '${OpenRouterChatEngine.WEB_SEARCH_FUNCTION_NAME}' was not made")
    }

    if (evalCase.checks.forbidToolAbsenceClaim) {
        val absencePatterns = listOf(
            "i can't browse",
            "i cannot browse",
            "i don't have internet access",
            "i do not have internet access",
            "i can't access the internet",
            "i cannot access the internet",
            "i can't search the web",
            "i cannot search the web"
        )
        if (absencePatterns.any { responseLower.contains(it) }) {
            failures.add("Response claims missing web capability")
        }
    }

    for (pattern in evalCase.checks.mustMatchRegex) {
        if (!Regex(pattern).containsMatchIn(response)) {
            failures.add("Regex did not match: '$pattern'")
        }
    }

    return failures
}

private fun writeSuiteArtifacts(result: SuiteResult, outputDir: Path) {
    mapper.writeValue(outputDir.resolve("results.json").toFile(), result)
    val summary = buildString {
        appendLine("# Eval Summary")
        appendLine()
        appendLine("- Suite: `${result.suite}`")
        appendLine("- Strict mode: `${result.strict}`")
        appendLine("- Skipped: `${result.skipped}`")
        if (result.skipped) {
            appendLine("- Reason: ${result.skipReason}")
        } else {
            appendLine("- Cases: ${result.passedCases}/${result.totalCases} passed")
            appendLine("- Score: ${(result.score * 100.0).roundToInt()}%")
            if (result.judgeSummary?.enabled == true) {
                appendLine()
                appendLine("## Judge Summary")
                appendLine("- Model: `${result.judgeSummary.model}`")
                appendLine("- Judged cases: ${result.judgeSummary.totalJudged}")
                appendLine("- Average overall score: ${result.judgeSummary.averageOverallScore ?: "n/a"} / 5")
                appendLine("- Average factuality: ${result.judgeSummary.averageFactuality ?: "n/a"}")
                appendLine("- Average relevance: ${result.judgeSummary.averageRelevance ?: "n/a"}")
                appendLine("- Average completeness: ${result.judgeSummary.averageCompleteness ?: "n/a"}")
                appendLine("- Average instruction following: ${result.judgeSummary.averageInstructionFollowing ?: "n/a"}")
                appendLine("- Judge pass rate: ${result.judgeSummary.passRate ?: "n/a"}")
                appendLine("- Threshold: ${result.judgeSummary.minAverageScore}")
                appendLine("- Threshold passed: ${result.judgeSummary.thresholdPassed ?: "n/a"}")
            }
            val failed = result.results.filter { !it.passed }
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("## Failed Cases")
                for (item in failed) {
                    appendLine("- `${item.id}`: ${item.failures.joinToString("; ")}")
                }
            }
        }
    }
    Files.writeString(outputDir.resolve("summary.md"), summary)
}

private fun loadDataset(path: Path): List<EvalCase> {
    if (!path.exists()) {
        throw IllegalArgumentException("Dataset not found: $path")
    }
    return path.readLines()
        .filter { it.isNotBlank() }
        .map { mapper.readValue(it, EvalCase::class.java) }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when (arg) {
            "--strict" -> {
                options["strict"] = "true"
                i += 1
            }
            "--judge" -> {
                options["judge"] = "true"
                i += 1
            }
            "--suite", "--dataset", "--output", "--judge-model", "--judge-min-score" -> {
                if (i + 1 >= args.size) {
                    throw IllegalArgumentException("Missing value for $arg")
                }
                options[arg.removePrefix("--")] = args[i + 1]
                i += 2
            }
            else -> {
                throw IllegalArgumentException("Unknown argument: $arg")
            }
        }
    }
    return options
}

private fun resolveApiKey(): String? {
    val fromEnv = System.getenv("OPENROUTER_API_KEY")
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val fromProperty = System.getProperty("openrouter.api.key")
    if (!fromProperty.isNullOrBlank()) return fromProperty
    return null
}

private fun resolveJudgeConfig(suite: String, options: Map<String, String>): JudgeConfig {
    val flagEnabled = options["judge"]?.toBooleanStrictOrNull() ?: false
    val envEnabled = (System.getenv("EVAL_JUDGE_ENABLED") ?: "false").toBooleanStrictOrNull() ?: false
    val enabled = flagEnabled || (suite == "full" && envEnabled)
    return JudgeConfig(
        enabled = enabled,
        model = options["judge-model"]
            ?: System.getenv("EVAL_JUDGE_MODEL")
            ?: JudgeClient.DEFAULT_MODEL,
        minAverageScore = options["judge-min-score"]?.toDoubleOrNull()
            ?: System.getenv("EVAL_JUDGE_MIN_AVG_SCORE")?.toDoubleOrNull()
            ?: 3.5,
        temperature = System.getenv("EVAL_JUDGE_TEMPERATURE")?.toDoubleOrNull() ?: 0.0
    )
}

private fun buildJudgeSummary(config: JudgeConfig, results: List<CaseResult>): JudgeSummary? {
    if (!config.enabled) return null
    val judged = results.mapNotNull { it.judgeResult }.filter { it.error == null }
    if (judged.isEmpty()) {
        return JudgeSummary(
            enabled = true,
            model = config.model,
            totalJudged = 0,
            averageOverallScore = null,
            averageFactuality = null,
            averageRelevance = null,
            averageCompleteness = null,
            averageInstructionFollowing = null,
            passRate = null,
            minAverageScore = config.minAverageScore,
            thresholdPassed = null
        )
    }

    fun avg(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()
    val overall = avg(judged.mapNotNull { it.overallScore })
    val factuality = avg(judged.mapNotNull { it.factuality?.toDouble() })
    val relevance = avg(judged.mapNotNull { it.relevance?.toDouble() })
    val completeness = avg(judged.mapNotNull { it.completeness?.toDouble() })
    val instruction = avg(judged.mapNotNull { it.instructionFollowing?.toDouble() })
    val passRate = avg(
        judged.mapNotNull {
            when (it.verdict) {
                "pass" -> 1.0
                "fail" -> 0.0
                else -> null
            }
        }
    )
    val thresholdPassed = overall?.let { it >= config.minAverageScore }

    return JudgeSummary(
        enabled = true,
        model = config.model,
        totalJudged = judged.size,
        averageOverallScore = overall,
        averageFactuality = factuality,
        averageRelevance = relevance,
        averageCompleteness = completeness,
        averageInstructionFollowing = instruction,
        passRate = passRate,
        minAverageScore = config.minAverageScore,
        thresholdPassed = thresholdPassed
    )
}
