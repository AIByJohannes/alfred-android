# Eval CI Contract

This project runs LLM evals as a separate verification lane (`:evals`) rather than as unit tests.

## PR Pipeline (smoke)

- Command: `./gradlew evalSmoke`
- Behavior: non-blocking by default; always upload `evals/reports/**` artifacts.
- Purpose: catch obvious behavioral regressions quickly.

## Main / Nightly Pipeline (full)

- Command: `./gradlew evalFull`
- Behavior: non-blocking by default; upload `evals/reports/**` artifacts and keep historical reports.
- Purpose: deeper quality drift detection and broader scenario coverage.
- Optional judge mode:
  - `EVAL_JUDGE_ENABLED=true`
  - `EVAL_JUDGE_MODEL=deepseek/deepseek-v3.2`

## Optional Strict Gates

- `./gradlew evalSmokeStrict`
- `./gradlew evalFullStrict`

Strict tasks exit non-zero on failed eval checks (or missing API key).

## Required Environment

- `OPENROUTER_API_KEY`: OpenRouter key used by eval and search models.
- Optional:
  - `EVAL_MODEL_MAIN`
  - `EVAL_MODEL_SEARCH`
  - `EVAL_JUDGE_ENABLED` (`true|false`)
  - `EVAL_JUDGE_MODEL` (default `deepseek/deepseek-v3.2`)
  - `EVAL_JUDGE_MIN_AVG_SCORE` (default `3.5`)
  - `EVAL_JUDGE_TEMPERATURE` (reserved; default `0.0`)
