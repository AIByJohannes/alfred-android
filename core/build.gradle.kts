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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
}
