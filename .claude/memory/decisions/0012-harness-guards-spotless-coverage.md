# 0012 — Harness guards + Spotless + coverage-floor tooling

**Date:** 2026-07-10
**Context:** Harness-improvement session (not a GitHub issue). Reviewed the existing
setup and added four automation gaps the harness didn't yet enforce mechanically.

## What was added

1. **Liquibase immutable-migration guard** — `.claude/hooks/liquibase-immutable-guard.sh`,
   wired as `PreToolUse:Edit|Write|MultiEdit` in `settings.json`. Exits 2 (blocks) when a
   tool would edit/overwrite an **existing** file under
   `src/main/resources/db/changelog/migrations/`. Brand-new migration files (path doesn't
   exist yet) and `db.changelog-master.xml` pass through. Enforces the long-standing rule
   from CLAUDE.md + [[liquibase-postgres-only-migrations-on-h2]] that already-run changesets
   are immutable. Reads `tool_input.file_path` from stdin JSON via python3.

2. **Security-review gate** — `.claude/hooks/security-review-gate.sh`, wired as a `Stop`
   hook. When the session's diff (working tree + staged + `@{upstream}..HEAD`) touches
   `security/`, `config/SecurityConfig.java`, `util/ApiKeyGenerator.java`,
   `util/ApiKeyHasher.java`, or `db/changelog/`, it prints a nudge to run `/security-review`.
   De-duped to one fire per unique sensitive-file-set per calendar day via
   `.claude/memory/.security-gate-guard` (same pattern as [[stop-hook-nudge-needs-commit-tracking]]).
   Automates the manual "Security review" gate in CLAUDE.md.

3. **Spotless (google-java-format)** — added to `pom.xml` (`spotless.version=2.43.0`,
   `google-java-format.version=1.24.0`, GOOGLE style + removeUnusedImports/trim/endWithNewline).
   `spotless:check` bound to `verify`, so the **existing** CI (`./mvnw verify`) enforces it —
   no `ci.yml` change needed. A `Stop` hook `.claude/hooks/format-changed.sh` runs
   `spotless:apply` scoped (`-DspotlessFiles=<regex>`) to changed `.java` files each session
   to keep the gate green; it self-skips when no JDK is on PATH.
   **Rollout requirement:** `./mvnw spotless:apply` must be run + committed once before
   `verify`/CI passes, otherwise it fails on all pre-existing unformatted files.

4. **Coverage-floor script + locked floor** — `.claude/scripts/coverage-floor.sh`. Measures
   real JaCoCo line coverage from `target/site/jacoco/jacoco.csv` (LINE_MISSED=$8,
   LINE_COVERED=$9; excludes `FeatureFlagApplication` + `*MapperImpl` to match the pom gate),
   suggests a floor of `current - 0.02`, and with `--apply` rewrites `<jacoco.line.coverage>`
   in `pom.xml`. Builds on [[0004-jacoco-coverage-ratchet-and-ci]] which left the floor at
   0.00. **Applied 2026-07-10:** measured coverage = **84.8%** (already above the 80% target),
   so the ratchet floor is now locked at **0.83** in `pom.xml`.

## Notes / alternatives

- **Format on Stop, not per-edit:** per-`Edit` `spotless:apply` pays Maven JVM startup on
  every keystroke — too slow. Stop-hook batching keeps CI green without the cost.
- **Java was missing at first** (`/usr/bin/java` stub, no JDK); installed mid-session via
  `brew install --cask temurin@21` (JDK 21.0.11, matches CI). Then `./mvnw spotless:apply`
  reformatted 103 files and `./mvnw verify` went green (165 tests, spotless + coverage pass).
  All hooks still self-skip when mvnw can't run (e.g. sandboxed CI-less runs).
- **Not built** (judged lower-value, already covered): a Postman/OpenAPI smoke-test skill and
  a dedicated security-reviewer agent — `review-pr` + built-in `/security-review` cover it.
- CLAUDE.md updated: "Code style" line (now Spotless), plus Security-review and new
  "Migrations are immutable" bullets under Development workflow.

## Rollout status (2026-07-10) — DONE
- `./mvnw spotless:apply` reformatted 103 files → committed as a separate `style:` commit.
- Coverage floor locked at 0.83 (measured 84.8%).
- `./mvnw verify` green end-to-end (spotless:check + 165 tests + jacoco:check).
- Committed on branch `feature/harness-guards` (style commit + chore(harness) commit). Push
  when ready; consider opening a PR into `develop` per gitflow.
