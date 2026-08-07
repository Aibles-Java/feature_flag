# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #37** (flag hygiene: stale detection + expiry) on branch `feature/issue-37-flag-hygiene`
(→ `develop`, cut off `develop` @ `04ac6bf`). `./mvnw clean verify` green — **271 tests, 0 failures**
(develop has 243, so +28), Spotless clean, coverage met. **Not yet pushed / no PR.**

- **Migration 013** — `flag_environment_states.last_evaluated_at`, `feature_flags.expires_at`, plus
  their indexes. Both nullable, so it is a non-blocking add.
- **`hygiene/FlagEvaluationTracker`** — two-layer throttle (Caffeine expiring set + threshold guard in
  the UPDATE). Wired into `EvaluationServiceImpl`, outside any cache-load path.
- **`FlagHygieneService` / `FlagHygieneController`** — `GET /api/v1/flag-hygiene?projectId=&status=`,
  paginated, VIEWER+.
- **`HygieneProperties`** (`app.hygiene.stale-after=30d`, `evaluation-touch-throttle=5m`), picked up
  by the app's `@ConfigurationPropertiesScan` — no config class needed.
- Admin DTOs gained `expiresAt` / `lastEvaluatedAt`. **The SDK `FlagEvaluationResponse` is unchanged**
  (an AC).

## ⚠️ Three things not to "simplify"

1. **`REQUIRES_NEW` on the touch queries is mandatory.** The evaluation path is
   `@Transactional(readOnly = true)`; joining it makes the write an UPDATE in a read-only transaction
   — PostgreSQL rejects it, **H2 allows it**, so removing the annotation passes every test and breaks
   production. `FlagHygieneIntegrationTest.evaluationPersistsLastEvaluatedAt` guards this.
2. **The touch must stay a bulk JPQL UPDATE**, which skips `@UpdateTimestamp`. Using the entity setter
   would bump `updated_at` on every SDK read.
3. **The tracker call must stay outside any cache-load function** — inside one, a cache hit (#30/PR
   #53) skips tracking and the hottest flags get reported stale.

## Context to Load

- `decisions/0023-flag-hygiene-stale-detection-expiry.md` — all three traps plus the reporting design.

## Next steps

1. Commit + push; open PR with `create-pr` (`Closes #37`); `.claude/scripts/issue-board.sh ready 37`.
2. Flag in the PR: `CLAUDE.md` collides trivially with PR #62 (both append before "v2 Roadmap");
   `/security-review` not needed here (no crypto/auth), but `/review-pr` is still worth running.

## Cross-branch / open PRs

- **#43** (issue #27, docker) — MERGEABLE, CI green. Decision **0019**.
- **#58** (issue #31, audit log) — MERGEABLE, CI green. Migration **011**, decision **0020**.
  Unanswered comment "check the warning please": every CI warning is pre-existing on develop
  (verified vs run `30373689296`); only `HHH90000025 H2Dialect` is worth fixing.
- **#60** (issue #34, GHCR + Trivy) — MERGEABLE, CI green. Decision **0018**. Raises the JaCoCo floor
  to 0.87; #58 measured 0.9099 and develop+#60 0.8938, so both clear it.
- **#61** (issue #35, percentage rollout) — MERGEABLE, CI green. Decision **0021**, ADR-0004.
- **#62** (issue #36, webhooks) — MERGEABLE, CI green. Migration **012**, decision **0022**, ADR-0005.
  A self-review pass fixed 5 findings (`a61e0e0`). **`/security-review` still not run** and warranted
  there (crypto-at-rest + SSRF guard).
- **#53** (issue #30, evaluation cache) — open; interacts with #37, see trap 3 above.
- Migrations: develop at 010 · **011 = #58** · **012 = #62** · **013 = #37 (this branch)**.
- Decisions: **0018** #60 · **0019** #43 · **0020** #58 · **0021** #61 · **0022** #62 · **0023** #37 —
  collision-free in any merge order.

## Known landmines

- **Any `@SpringBootTest` inserting a `FeatureFlag` needs `NON_KEYWORDS=KEY,VALUE`** in its H2 URL —
  `key` is reserved in H2 2.x and the insert dies with a bare syntax error.
- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`): while both paths
  are tracked the phantom one is always dirty and **`git merge` refuses to start**; `git stash` only
  flips which is dirty. Fix: `git rm --cached docs/ARCHITECTURE.md`. Branches cut off current develop
  never had it.
- `./mvnw test -Dtest='A+B'` is invalid surefire syntax — use `-Dtest='A,B'`.
- Beans with internal caches (`FlagEvaluationTracker`, rate limiter) keep state **across test methods**
  in one Spring context.
