plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.openai.java)
    implementation(libs.koog.openrouter.client.jvm)
    implementation(libs.coroutines.core)
    implementation(libs.koog.http.client.ktor)
    runtimeOnly(libs.ktor.client.okhttp)
}
