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

---

## 4. Wireless APK Deployment

The Galaxy S25 Ultra can receive debug builds over wireless ADB. The phone and
development machine must be on the same network, and **Developer options >
Wireless debugging** must remain enabled on the phone.

### Local Configuration (`local.properties`)

To make connections and pairing easier without committing private IP addresses, configure these properties in your git-ignored `local.properties` file:

```properties
# The IP address & port shown under "Wireless debugging" on your device
adb.device.ip=192.168.178.105:44305
```

### Pair the Phone

Pairing is normally required only once per machine:

#### Option A: Run Gradle Pair task (Command Line)
You can trigger the pairing directly using the Gradle wrapper by passing your pairing details:
```powershell
.\gradlew.bat adbPair -Padb.pairing.ip=<pairing-ip-and-port> -Padb.pairing.code=<pairing-code>
```

#### Option B: Configure and Run Gradle Pair task
Alternatively, set the temporary pairing variables in `local.properties`:
```properties
adb.device.pairing.ip=192.168.178.105:34567
adb.device.pairing.code=123456
```
And run:
```powershell
.\gradlew.bat adbPair
```

#### Option C: Manual pairing
1. On the phone, open **Developer options > Wireless debugging > Pair device with pairing code**.
2. Pair manually:
   ```powershell
   adb pair <phone-ip>:<pairing-port> <pairing-code>
   ```

### Connect, Build and Install

Once paired, running the install task will **automatically connect** to the IP specified by `adb.device.ip` in `local.properties`:

```powershell
.\gradlew.bat installDebug
```

This task dependencies flow automatically: `installDebug` -> `adbConnect` (runs `adb connect <adb.device.ip>`).

You can also run the connection step independently at any time:
```powershell
.\gradlew.bat adbConnect
```

If `adb devices -l` lists the same phone both by IP address and by mDNS service name, remove the redundant explicit connection before deploying:
```powershell
adb disconnect <phone-ip>:<connect-port>
```
This prevents Gradle from installing the same APK twice on the same physical device.
