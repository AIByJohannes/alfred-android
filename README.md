# A.L.F.R.E.D. App for Android
![Kotlin](https://img.shields.io/badge/-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/-Gradle-02303A?style=flat&logo=gradle&logoColor=white)

## Development Setup

To easily interact with the OpenRouter API during development without manually typing the API key in the app:
1. Create or open the `local.properties` file in the root directory.
2. Add your OpenRouter API key:
   ```properties
   OPENROUTER_API_KEY=sk-or-v1-your-key-here
   ```
This key will be bundled directly into your debug builds.

## LLM Evals

LLM evals run as a separate verification lane in the `:evals` module, not as unit tests.

### Run smoke evals (fast)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
.\gradlew evalSmoke
```

### Run full evals (slow/expensive)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
.\gradlew evalFull
```

### Run full evals with LLM judge (DeepSeek)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
$env:EVAL_JUDGE_ENABLED = 'true'
$env:EVAL_JUDGE_MODEL = 'deepseek/deepseek-v3.2'
.\gradlew evalFull
```

Judge mode is reference-free and enabled for `full` suite only by default when `EVAL_JUDGE_ENABLED=true`.

### Strict mode (non-zero exit on regressions)

```powershell
.\gradlew evalSmokeStrict
.\gradlew evalFullStrict
```

### Output artifacts

Each run writes artifacts to `evals/reports/<suite>-<timestamp>/`:

- `results.json`: machine-readable case results and score
- `summary.md`: human-readable summary
- `traces/<case-id>.json`: per-case traces, tool calls, and failures

If `OPENROUTER_API_KEY` is missing, evals are skipped by default and recorded as skipped in reports. Strict tasks fail in that case.
When judge mode is enabled, strict tasks also fail if average judge score is below `EVAL_JUDGE_MIN_AVG_SCORE` (default `3.5`).
