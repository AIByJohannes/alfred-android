import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val localOpenRouterApiKey = localProperties.getProperty("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }

fun JavaExec.configureOpenRouterApiKey() {
    localOpenRouterApiKey?.let { systemProperty("openrouter.api.key", it) }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.openai.java)
}

tasks.register<JavaExec>("smokeEval") {
    group = "verification"
    description = "Runs fast smoke LLM eval scenarios."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "smoke", "--dataset", "datasets/smoke.jsonl")
    configureOpenRouterApiKey()
}

tasks.register<JavaExec>("fullEval") {
    group = "verification"
    description = "Runs full LLM eval scenarios."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "full", "--dataset", "datasets/full.jsonl")
    configureOpenRouterApiKey()
}

tasks.register<JavaExec>("smokeEvalStrict") {
    group = "verification"
    description = "Runs smoke evals and fails on regressions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "smoke", "--dataset", "datasets/smoke.jsonl", "--strict")
    configureOpenRouterApiKey()
}

tasks.register<JavaExec>("fullEvalStrict") {
    group = "verification"
    description = "Runs full evals and fails on regressions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "full", "--dataset", "datasets/full.jsonl", "--strict")
    configureOpenRouterApiKey()
}
