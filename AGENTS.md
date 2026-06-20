# Agent Guidelines for alfred-android

This file provides practical guidance for AI agents working on this repository.

## Build Times

| Task | Typical Duration |
|---|---|
| `.\gradlew.bat assembleDebug` (APK build) | **~3 minutes** (cold), ~1 min (incremental) |
| `.\gradlew.bat test` (unit tests) | **~3 minutes** (cold), ~1 min (incremental) |
| `.\gradlew.bat evalSmoke` | ~2–5 minutes (requires API key) |
| `.\gradlew.bat evalFull` | ~10–20 minutes (requires API key) |

> **Important for agents**: Always set a **3-minute timer** when launching an APK build or unit test run. Do **not** poll status every 20–30 seconds — the Gradle daemon takes time to warm up and compile. A single 3-minute wait avoids unnecessary interruptions.

## Running Builds

Use the Gradle wrapper directly (the `just` CLI tool may not be installed). If a device is connected via ADB, prefer installing directly:

```powershell
.\gradlew.bat installDebug    # Build and install on connected device (preferred for ADB deployment)
.\gradlew.bat assembleDebug   # Build debug APK only
.\gradlew.bat test            # Run unit tests
```

The system Java on PATH (Temurin 22) works fine — do **not** set `JAVA_HOME` to the Adoptium JDK-21 path referenced in `justfile`, as that path may not exist on all machines.

## APK Output

The debug APK is always written to:

```
app\build\outputs\apk\debug\app-debug.apk
```

## Key Architecture

- **`core/`** — platform-agnostic chat engine (`OpenRouterChatEngine`), models, search clients
- **`app/`** — Android UI, ViewModels, data stores, settings
- **`evals/`** — LLM evaluation harness (separate Gradle module, not unit tests)

## Important Files

| File | Purpose |
|---|---|
| [`ApiKeyStore.kt`](app/src/main/java/com/aibyjohannes/alfred/data/ApiKeyStore.kt) | Stores API key + selected model in `EncryptedSharedPreferences` |
| [`ChatRepository.kt`](app/src/main/java/com/aibyjohannes/alfred/data/ChatRepository.kt) | Bridges ViewModel ↔ `OpenRouterChatEngine`; loads model from store |
| [`HomeViewModel.kt`](app/src/main/java/com/aibyjohannes/alfred/ui/home/HomeViewModel.kt) | Chat UI state; handles conversation switching |
| [`SettingsFragment.kt`](app/src/main/java/com/aibyjohannes/alfred/ui/settings/SettingsFragment.kt) | Settings screen: API key + language model dropdown |
| [`OpenRouterChatEngine.kt`](core/src/main/kotlin/com/aibyjohannes/alfred/core/engine/OpenRouterChatEngine.kt) | Streaming chat completions via OpenRouter |
| [`arrays.xml`](app/src/main/res/values/arrays.xml) | Available language model labels and OpenRouter model IDs |

## Git Conventions

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: short imperative description

Optional longer explanation of why, not what.
```

No ticket prefix is used (the branch is `main`). Never amend pushed commits.
