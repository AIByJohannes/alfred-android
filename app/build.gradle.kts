import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { inputStream ->
            load(inputStream)
        }
    }
}

android {
    namespace = "com.aibyjohannes.alfred"
    compileSdk = 36

    val openRouterKey: String = localProperties.getProperty("OPENROUTER_API_KEY") ?: ""

    defaultConfig {
        applicationId = "com.aibyjohannes.alfred"
        minSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.documentfile)

    // OpenAI Java SDK (includes OkHttp internally)
    implementation(libs.openai.java)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Security for encrypted storage
    implementation(libs.security.crypto)

    // Markdown
    implementation(libs.markwon.core)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.koog.openrouter.client.jvm)
    testImplementation(libs.koog.http.client.ktor)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.register("adbConnect") {
    group = "install"
    description = "Connects to the ADB device specified by adb.device.ip in local.properties"
    doLast {
        val ip = localProperties.getProperty("adb.device.ip")
        if (!ip.isNullOrBlank()) {
            println("Connecting to ADB device: $ip")
            val sdkDir = localProperties.getProperty("sdk.dir")
            val adbPath = if (!sdkDir.isNullOrBlank()) {
                val adbFile = File(sdkDir, "platform-tools/adb.exe")
                if (adbFile.exists()) adbFile.absolutePath else "adb"
            } else {
                "adb"
            }
            try {
                providers.exec {
                    commandLine(adbPath, "connect", ip)
                }.result.get()
            } catch (e: Exception) {
                println("Warning: Failed to run adb connect to $ip: ${e.message}")
            }
        } else {
            println("adb.device.ip not set in local.properties. Skipping adbConnect.")
        }
    }
}

tasks.register("adbPair") {
    group = "install"
    description = "Pairs an ADB device using adb.pairing.ip and adb.pairing.code properties"
    doLast {
        val pairingIp = project.findProperty("adb.pairing.ip") as? String
            ?: localProperties.getProperty("adb.device.pairing.ip")
        val pairingCode = project.findProperty("adb.pairing.code") as? String
            ?: localProperties.getProperty("adb.device.pairing.code")
        
        if (!pairingIp.isNullOrBlank() && !pairingCode.isNullOrBlank()) {
            println("Pairing with ADB device at $pairingIp...")
            val sdkDir = localProperties.getProperty("sdk.dir")
            val adbPath = if (!sdkDir.isNullOrBlank()) {
                val adbFile = File(sdkDir, "platform-tools/adb.exe")
                if (adbFile.exists()) adbFile.absolutePath else "adb"
            } else {
                "adb"
            }
            try {
                providers.exec {
                    commandLine(adbPath, "pair", pairingIp, pairingCode)
                }.result.get()
            } catch (e: Exception) {
                println("Warning: Failed to run adb pair with $pairingIp: ${e.message}")
            }
        } else {
            println("Please specify pairing IP and code in local.properties (adb.device.pairing.ip/code) or via Gradle properties:")
            println("  .\\gradlew.bat adbPair -Padb.pairing.ip=<IP:PORT> -Padb.pairing.code=<CODE>")
        }
    }
}

afterEvaluate {
    tasks.named("installDebug") {
        dependsOn("adbConnect")
    }
}

