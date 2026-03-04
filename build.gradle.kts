// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("evalSmoke") {
    group = "verification"
    description = "Runs the smoke LLM eval suite."
    dependsOn(":evals:smokeEval")
}

tasks.register("evalFull") {
    group = "verification"
    description = "Runs the full LLM eval suite."
    dependsOn(":evals:fullEval")
}

tasks.register("evalSmokeStrict") {
    group = "verification"
    description = "Runs smoke LLM evals in strict mode."
    dependsOn(":evals:smokeEvalStrict")
}

tasks.register("evalFullStrict") {
    group = "verification"
    description = "Runs full LLM evals in strict mode."
    dependsOn(":evals:fullEvalStrict")
}
