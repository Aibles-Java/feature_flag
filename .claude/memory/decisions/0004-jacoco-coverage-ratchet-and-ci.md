# 0004 — JaCoCo coverage ratchet + CI test gate

**Date:** 2026-07-01
**Issue:** [#3](https://github.com/Aibles-Java/feature_flag/issues/3) — closed by [PR #7](https://github.com/Aibles-Java/feature_flag/pull/7) (→ `develop`)

## What was decided

Wire JaCoCo into Maven `verify` and add a GitHub Actions CI gate, but set the coverage
threshold to a **ratchet starting at `0.00`** rather than enforcing 80% immediately.

- `jacoco-maven-plugin` 0.8.13, three executions in `pom.xml`:
  - `prepare-agent` — instruments the JVM before tests
  - `report` (bound to `verify`) — writes `target/site/jacoco/` (HTML + `jacoco.xml`)
  - `check` (bound to `verify`) — BUNDLE LINE COVEREDRATIO ≥ `${jacoco.line.coverage}`
- The floor lives in a POM property `jacoco.line.coverage` (currently `0.00`) so raising
  it is a one-line edit. Excludes: `FeatureFlagApplication.class` and `**/*MapperImpl.class`
  (entry point + generated MapStruct impls carry no logic worth gating).
- `.github/workflows/ci.yml` runs `./mvnw verify` on PRs into `develop`/`main` and pushes
  to those branches; Temurin JDK 21 + Maven cache; uploads the JaCoCo HTML report as an
  artifact; `concurrency` cancels superseded runs per ref.

## Why

The repo has ~0% real test coverage (only the default `contextLoads` test). Enforcing the
80% target from `CLAUDE.md` today would make CI permanently red and block every PR. The
ratchet gives us the *mechanism* now (plugin wired, gate green) so the number can be raised
incrementally as real tests land — turning the aspirational 80% into an enforceable,
regression-proof floor without a big-bang test-writing prerequisite.

## Notes / gotchas

- Verified end-to-end: `./mvnw verify` → BUILD SUCCESS locally, and the new workflow ran
  green on its own PR (#7) — the workflow-on-first-PR self-test works because `ci.yml` is
  present on the feature branch.
- Also removed a duplicate `spring-security-test` `<dependency>` block found in `pom.xml`.
- Next lever: bump `jacoco.line.coverage` as the companion test-coverage issues land.
- Related: [[0001-claude-code-harness-setup]] (the 80% target this enforces).
