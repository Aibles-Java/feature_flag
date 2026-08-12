# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #48** (code graph Track A Tier-1: ArchUnit architecture governance gate) on branch
`feature/issue-48-archunit-governance` (→ `develop`). Implementation **complete and verified**;
board card moved to *In progress*. Not yet committed/pushed at the time of writing.

`./mvnw verify` green: **257 tests, 0 failures, Spotless clean, JaCoCo floor met**, with
`ArchitectureTest` 7/7 visible in the Surefire output.

Files:

- `pom.xml` — `archunit.version=1.4.2` property + `archunit-junit5` test-scope dependency.
- `src/test/java/org/aibles/feature_flag/architecture/ArchitectureTest.java` — R1–R7 (new).
- `src/test/resources/archunit.properties` + `src/test/resources/archunit_store/` — the frozen
  R7 baseline. **The store must be committed** or the frozen rule silently passes.
- `docs/adr/ADR-0004-archunit-architecture-governance.md` (new) + `docs/adr/README.md` index
  (also backfilled the missing ADR-0003 row).
- `docs/architecture.md` — new "Architecture governance (enforced, not just documented)" section.
- `CLAUDE.md` — one gate bullet under *Development workflow*.

**Both negative tests were performed and reverted** (see the decision file) — that, not the green
run, is the proof the gate is live. Working tree is clean of the probes; verified by `git status`
and by the store's md5 being unchanged after the failing run.

## Context to Load

- `decisions/0022-archunit-tier1-governance-gate.md` — the three ways the spec's rule sketch
  doesn't compile/pass as written, the freeze configuration gotchas, and how to run the negative
  test without a false green.
- `decisions/0014-codegraph-adoption.md` — the parent decision (Goal A vs Goal B split).

## Next steps

1. Commit (code + memory together — the pre-push gate blocks code-only pushes), push, open the PR
   against `develop` via the `create-pr` skill, then
   `.claude/scripts/issue-board.sh ready 48`.
2. Confirm the CI run log shows `ArchitectureTest` (acceptance criterion — CI runs
   `./mvnw --batch-mode --no-transfer-progress verify`, so no pipeline change was needed).
3. Follow-ups, both out of scope here and worth separate issues:
   - **Tier 2** rules (spec §A.4): immutable `FeatureFlag.key` via a field-set condition, and
     `FeatureFlagServiceImpl.update()` must not call `UpdateFeatureFlagRequest.getKey()`.
   - **Unfreeze R7** by breaking the `config` ↔ `security` cycle (relocate `JwtProperties`) —
     a sensitive-area refactor, deliberately not bundled with the governance PR.
