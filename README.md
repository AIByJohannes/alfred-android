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

For building and installing a debug APK directly on a wirelessly connected
Android phone, see [Wireless APK Deployment](docs/developer-setup-notes.md#4-wireless-apk-deployment).

## Chat History Storage

On first launch, Alfred asks the user to choose a parent folder for chat history. The app creates a fixed `Alfred/` subfolder inside that location and stores chat data there so users can inspect or back up their histories directly.

The storage layout is:

- `Alfred/metadata.json` stores workspaces, conversation summaries, active workspace, active conversation per workspace, and ID counters.
- `Alfred/workspace-<id>-<initial-slug>/conversation-<id>.jsonl` stores one chat per JSONL file.

Workspace folder names are stable after creation. Renaming a workspace updates `metadata.json` only and does not rename the folder.

## Agent Skills

Alfred discovers documentation skills from the same user-selected storage location. Add each skill under `Alfred/skills/<skill-id>/SKILL.md`:

```text
Alfred/
  skills/
    meeting-prep/
      SKILL.md
      references/
        checklist.md
```

Each `SKILL.md` must start with YAML frontmatter containing a lowercase, hyphenated `name` that matches its folder and a non-empty `description`. Alfred adds only this catalog metadata to the prompt, then loads matching instructions and referenced `.md` or `.txt` files on demand. Skill files are rescanned at the start of every chat turn, so external edits apply without restarting the app.

Chats can also manage skills through Alfred tools: create a new skill, rename an existing skill, and write `.md` or `.txt` reference files inside a skill directory. Renaming preserves bundled files and updates the `name` frontmatter in `SKILL.md`. Scripts, binary assets, remote downloads, and paths outside the skill directory are not supported.

## LLM Evals

LLM evals run as a separate verification lane in the `:evals` module, not as unit tests.

If you have `just` installed, you can run them using the `just` recipes (which dynamically pick up your environment's `JAVA_HOME`).

### Run smoke evals (fast)

```powershell
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
just eval-smoke
```
*Or manually:*
```powershell
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
.\gradlew evalSmoke
```

### Run full evals (slow/expensive)

```powershell
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
just eval-full
```
*Or manually:*
```powershell
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
.\gradlew evalFull
```

### Run full evals with LLM judge (DeepSeek)

```powershell
$env:OPENROUTER_API_KEY = 'sk-or-v1-your-key-here'
$env:EVAL_JUDGE_ENABLED = 'true'
$env:EVAL_JUDGE_MODEL = 'deepseek/deepseek-v3.2'
just eval-full
```
*Or manually:*
```powershell
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
