# Developer Setup & Build Notes (Windows Environment)

During testing and verification of the build system on this Windows machine, several environmental specifics and Gradle behaviors were identified and resolved. This document details those findings to ensure smooth development, testing, and debugging in the future.

---

## 1. Installed Java Environments

The host has the following Java environments pre-installed or embedded:

- **Global Eclipse Adoptium OpenJDK 22**:
  - Path: `C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot`
- **Embedded Android Studio JetBrains Runtime (JDK 21)**:
  - Path: `C:\Program Files\Android\Android Studio\jbr`
- **Embedded IntelliJ IDEA JetBrains Runtime (JDK 21)**:
  - Path: `C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.5\jbr`

> [!NOTE]
> The `justfile` and some commands in the `README.md` previously hardcoded `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot` or similar paths, which do not exist on this machine. Use the paths above for `JAVA_HOME` configuration.

---

## 2. Gradle Toolchain & Offline Build Resolution

The project (`app` and `kotlin`) is configured to use **Java Toolchain version 17**:
```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
```

### The Problem
When running Gradle builds, Gradle's Foojay toolchain resolver attempted to download JDK 17 automatically from remote repositories, but failed due to sandbox constraints or network connectivity issues. Additionally, Gradle's automated extraction and directory renaming routines sometimes hit locks or path resolution limits.

### The Solution
1. **Manual Extraction**:
   We manually extracted the pre-downloaded JDK 17 archive located in the Gradle cache:
   - Source: `C:\Users\johan\.gradle\jdks\OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10.zip`
   - Target: `C:\Users\johan\.gradle\manual-jdks\jdk-17`

2. **Global Gradle Properties Registration**:
   To prevent Gradle from attempting remote queries and instead point it directly to this extracted JDK 17, we created a global `gradle.properties` file:
   - Path: `C:\Users\johan\.gradle\gradle.properties`
   - Content:
     ```properties
     org.gradle.java.installations.paths=C:/Users/johan/.gradle/manual-jdks/jdk-17
     ```

With this configuration, Gradle's toolchain auto-detection successfully registers the offline JDK 17 and builds the project seamlessly without any remote network lookups.

---

## 3. Build & Execution Workflow

### `just` CLI is Missing
The host does **not** have the `just` task runner installed or available in the system `PATH`. Running `just build` or similar commands will result in command-not-found errors.

### Manual Commands to Build & Test

Always run the build commands using the Adoptium JDK 22 path as `JAVA_HOME` to launch the Gradle wrapper:

#### Build Debug APK
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat assembleDebug
```
*Output location:* `app\build\outputs\apk\debug\app-debug.apk`

#### Run Unit Tests
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat test
```

#### Run Evals (Smoke/Full)
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
.\gradlew.bat evalSmoke
```
