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

- `Alfred/workspaces/<uuid>-<initial-slug>/workspace.jsonl` stores workspace creation, renaming and deletion events.
- `Alfred/workspaces/<uuid>-<initial-slug>/conversation-<uuid>.jsonl` stores each chat's creation, messages, title and delete/restore events.
- A local Room database is a disposable read-model cache; JSONL files remain authoritative. Root-bound summaries survive reopen, while individual chat messages load on demand. Legacy metadata and workspace folders migrate when opened.

Workspace folder names are stable after creation. Renaming appends an event without renaming the folder. Turn saves fold and index only the affected conversation, including tool and reasoning metadata. Dirty-cache markers force authoritative recovery after interrupted writes; clean state is published only after indexing and required selections succeed. Writes through another store in the same process invalidate warm snapshots for the same resolved root. Background reconciliation refreshes externally edited history after initialization without holding the foreground writer lock during conversation reads; an explicit index rebuild also remains available.

New Chat immediately clears visible history and blocks input until the real conversation is persisted and loaded. It preserves previous chat files and reuses an already empty committed chat. Navigation is serialized so selected IDs, visible messages and prompt history commit together; failed reversible navigation restores the previous context.

Selecting a replacement history root cancels old work before adopting its storage and repository. Re-selecting the same resolved root preserves the active chat. Provider setup runs off the UI thread; unavailable folders use the existing folder-selection recovery dialog.

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
