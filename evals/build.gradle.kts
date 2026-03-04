plugins {
    alias(libs.plugins.kotlin.jvm)
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
}

tasks.register<JavaExec>("fullEval") {
    group = "verification"
    description = "Runs full LLM eval scenarios."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "full", "--dataset", "datasets/full.jsonl")
}

tasks.register<JavaExec>("smokeEvalStrict") {
    group = "verification"
    description = "Runs smoke evals and fails on regressions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "smoke", "--dataset", "datasets/smoke.jsonl", "--strict")
}

tasks.register<JavaExec>("fullEvalStrict") {
    group = "verification"
    description = "Runs full evals and fails on regressions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aibyjohannes.alfred.evals.EvalRunnerKt")
    args("--suite", "full", "--dataset", "datasets/full.jsonl", "--strict")
}
