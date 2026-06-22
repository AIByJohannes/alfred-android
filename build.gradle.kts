// Room's schema verifier extracts sqlite-jdbc during annotation processing. Some Windows
// launchers report C:\\WINDOWS as the JVM temp directory, so keep processor temp files in Gradle's cache.
val roomProcessorTempDir = layout.projectDirectory.dir(".gradle/room-tmp").asFile.apply { mkdirs() }
System.setProperty("java.io.tmpdir", roomProcessorTempDir.absolutePath)
System.setProperty("org.sqlite.tmpdir", roomProcessorTempDir.absolutePath)

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":app"))
    kover(project(":core"))
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

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.R",
                    "*.R$*",
                    "*.BuildConfig",
                    "com.aibyjohannes.alfred.databinding.*",
                    "com.aibyjohannes.alfred.MainActivity*",
                    "com.aibyjohannes.alfred.ui.settings.SettingsFragment*",
                    "com.aibyjohannes.alfred.ui.home.HomeFragment*",
                    "com.aibyjohannes.alfred.ui.home.ChatAdapter*",
                    "com.aibyjohannes.alfred.ui.home.ConversationAdapter*",
                    "com.aibyjohannes.alfred.ui.home.DrawerProjectsAdapter*",
                    "com.aibyjohannes.alfred.ui.home.VoiceOrAnimator*",
                    "com.aibyjohannes.alfred.ui.home.WorkspaceChipAdapter*",
                    "com.aibyjohannes.alfred.ui.home.AudioRecorder*",
                    "com.aibyjohannes.alfred.data.ticktick.TickTickOAuthServer*",
                    "com.aibyjohannes.alfred.notifications.AlfredNotificationReceiver*",
                    "com.aibyjohannes.alfred.notifications.NotificationBootReceiver*"
                )
            }
        }
    }
}
