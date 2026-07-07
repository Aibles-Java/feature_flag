# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #25** (Actuator health/liveness/readiness) on branch `feature/issue-25-actuator-health`
(→ `develop`). Implemented, reviewed, **merged with `develop`, and PUSHED**. PR **#42 OPEN**,
**MERGEABLE** (no conflict); CI (`Build & test (Java 21)`) was **pending** at push — check it's
green before merging.

This session:
- Reviewed PR #42 — exposure/permit design is sound. One design note (not blocking): the
  Dockerfile `HEALTHCHECK` targets DB-dependent `readiness`, so a DB blip marks the *container*
  unhealthy (Swarm / `depends_on: service_healthy` would react). Container health conventionally
  maps to `liveness`; readiness was a conscious choice. Also: DB-down readiness→503 path is
  untested (H2 always up). Both logged as follow-ups below.
- Merged `develop` (which now has #26 rate-limiting) into the #25 branch to clear the predicted
  `SecurityConfig.java` conflict — **kept both** (`permitAll /actuator/health/**` + the rate-limit
  filter wiring). Merge commit `8568793`, pushed.
- **Verified** the merged result: `AuthRateLimitFilter.shouldNotFilter` (skips non-`/auth`) and
  `JwtAuthenticationFilter` (no-ops without a Bearer) both leave `/actuator/health/**` untouched
  even though they run on the admin chain → new convention
  `permitall-does-not-skip-servlet-filters`.

**Merge gotcha (fixed this session):** resolving the merge took `develop`'s `MEMORY.md` +
`HANDOFF.md`, which **dropped the `0010-actuator` index line** from `MEMORY.md` (the decision file
itself survived). Restored it during `/save-memory`. Watch for this whenever merging `develop`
into a feature branch — index/handoff additions can get clobbered even when the content files are
fine.

## Context to Load

- `decisions/0010-actuator-health-endpoints.md` — exposure + permit rules + probe/readiness design.
- `conventions/permitall-does-not-skip-servlet-filters.md` — permitAll ≠ skip filters; verify each
  custom filter self-skips a new anonymous path.

## Next steps

1. Watch PR #42 CI → green, then merge to `develop` (squash/merge per repo convention).
2. `.claude/scripts/issue-board.sh ready 25` — move the board card to *Ready For Testing*
   (may already be done; verify). gh at `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe` (NOT on
   PATH; prepend it).
3. Commit + push this session's memory (memory gate) — likely a separate `chore(memory)` commit
   since the code was already pushed.

**Parked / cross-branch:**
- **#26** (rate limiting) — PR **#41** open on `feature/issue-26-rate-limiting`; holds decision
  0009 + conventions `spring-security-filter-order-anchor`, `second-springboottest-context-shared-h2`.
- **#24** (hash SDK API keys) — MERGED to develop (PR #40).
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10` pending.
- Issue #17 (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Uncommitted `docs/architecture.md` — unrelated; land or discard separately.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.

**Follow-ups:**
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness` → `liveness` (or confirm intent); add a
  DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- Raise `jacoco.line.coverage` above 0.00.
