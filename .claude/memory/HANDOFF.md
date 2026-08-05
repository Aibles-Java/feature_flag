# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #35** (identifier-based percentage rollout) on branch
`feature/issue-35-percentage-rollout` (→ `develop`, cut fresh off `develop` @ `04ac6bf`).
Implemented + `./mvnw clean verify` green (**274 tests, 0 failures**, Spotless clean, coverage met).
**Not yet pushed / no PR** — the memory commit lands first (the gate needs `.claude/memory/`).

The issue's four *code* scope bullets were **already on `develop`** (landed via
`feat/rollout-percent`, issue never closed). What this branch actually adds:

- `util/RolloutEvaluator.java` — **fixed a real bug**: `Math.abs(hash) % 100` → `(hash &
  Integer.MAX_VALUE) % BUCKETS`. Extracted package-private `toBucket(int)` + `bucketFor(id, key)`
  seams (the bug is unreachable through the public API). Full contract Javadoc.
- `util/RolloutEvaluatorTest.java` (new, 24 tests) — the acceptance criteria: determinism,
  monotonicity, chi-square uniformity over 10,000 identifiers, per-flagKey independence, the
  `toBucket` sign regression, documented edge cases. All identifiers generated, never random.
- `docs/adr/ADR-0004-percentage-rollout-contract.md` (new) — fail-open contract, cache invariant,
  sign-bug rationale, alternatives rejected. Also added the **missing ADR-0003 row** to
  `docs/adr/README.md` (the pagination PR never indexed it).
- `controller/sdk/EvaluationController.java` — `@Parameter(description = …)` on both `identifier`
  params. NOTE: this is the **first method-level springdoc annotation in the codebase** (only
  `OpenApiConfig` used swagger models before) — call it out in review.
- `EvaluationServiceImplTest` (+5 tests) — fail-open contract at the API boundary.
- `FeatureFlagControllerTest` (+2 tests) — first tests for the `@Min(0)/@Max(100)` admin validation
  (wired but previously unproven).

## Context to Load

- `decisions/0021-percentage-rollout-contract-issue-35.md` — the contract, the bug, the tests.
- `conventions/stale-issue-scope-verify-before-implementing.md` — why to grep before implementing.

## Next steps

1. Commit + push `feature/issue-35-percentage-rollout`.
2. Open PR with `create-pr` (`Closes #35`); then `.claude/scripts/issue-board.sh ready 35`.
3. In the PR, flag for the reviewer: (a) SDK `enabled` now means *effective per identifier* — a
   meaning change, though not a shape change; (b) fail-open makes a partial rollout bypassable —
   deliberate, see ADR-0004; (c) the new springdoc annotation style.

## Cross-branch / open PRs (all three conflict-resolved this session — MERGEABLE + CI green)

- **#43** (issue #27, docker port/non-root) — merged `develop` in; kept #25's readiness HEALTHCHECK
  layered under `USER spring`; compose now passes `APP_JWT_SECRET` (the image bakes the prod profile,
  so it would have crash-looped). Decision **0019**.
- **#58** (issue #31, audit log) — merged `develop` in; **migration renumbered 010 → 011** (#32 took
  010 for refresh-tokens; git did NOT flag it — two different filenames both added); kept develop's
  `@Transactional(readOnly=true)` on `listMembers` (the #52 fix). Decision **0020**.
- **#60** (issue #34, GHCR publish + Trivy) — merged `develop` in; verified the raised
  `jacoco.line.coverage=0.87` still holds after #32 landed (measured 0.8938).
  Decision **0018**.
- Decision numbers across open PRs: 0018 (#60) / 0019 (#43) / 0020 (#58) / **0021 (#35, this
  branch)** — collision-free in any merge order.
- **#53** (issue #30, evaluation cache) — open; its pre-rollout `FlagStateSnapshot` design is what
  satisfies #35's caching bullet. ADR-0004 records the invariant any future cache layer must keep.
- Unanswered review comment on **#58**: "check the warning please" — every CI warning is
  pre-existing on `develop` (verified by diffing against run `30373689296`); the only one worth
  fixing is `HHH90000025 H2Dialect ... specified explicitly` (drop `hibernate.dialect` from
  `application-test.properties`). Awaiting the reviewer's preference.

## Known landmines

- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`): while both
  paths are tracked the phantom one is *always* dirty and **`git merge` refuses to start** —
  `git stash` only flips which name is dirty. Fix is `git rm --cached docs/ARCHITECTURE.md`.
  `develop` renamed the uppercase file to `docs/architecture-design-v1.md`; PRs #43 and #58 each
  carry the `git rm --cached` plus an updated `conventions/windows-docs-case-collision.md`. This
  branch is off `develop` so it never had the phantom — do **not** re-update that convention file
  here, it would conflict three ways.
- `./mvnw test -Dtest='A+B'` is not valid surefire syntax — use `-Dtest='A,B'`.
