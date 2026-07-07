# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #26** (rate limiting) on branch `feature/issue-26-rate-limiting` (→ `develop`).
Implementation complete + fully verified — **not yet committed/pushed**.

Done (new package `security/ratelimit/`):
- `RateLimitProperties` (`@ConfigurationProperties app.rate-limit.*`), `RateLimitService`
  (Bucket4j + **Caffeine** `expireAfterAccess=2× refill` to bound the bucket maps),
  `AbstractRateLimitFilter` (429 + `Retry-After` ProblemDetail), `AuthRateLimitFilter`
  (per-IP `/api/v1/auth/**`), `SdkRateLimitFilter` (per-env-id `/api/v1/sdk/**`).
- `SecurityConfig` wires both, anchored on `UsernamePasswordAuthenticationFilter`.
- `pom.xml`: `bucket4j_jdk17-core:8.19.0` + Caffeine (BOM-managed).
- Properties: enabled in main, `enabled=false` in test profile.
- Tests: `RateLimitServiceTest` (unit), `RateLimitIntegrationTest` (own H2 DB, low caps,
  asserts 429 + Retry-After for BOTH chains). **47/47 pass.**

Reviews: security **clean**; code review's one Important finding (unbounded bucket map)
**fixed** via Caffeine. Two documented follow-ups (NOT in this PR): invalid-key SDK traffic
is unthrottled (needs a per-IP SDK limit); distributed backend (Redis) for multi-instance.

Numbering: used decision **0009** (not 0008 — #24 holds 0008 on its own unmerged branch).

## Context to Load

- `decisions/0009-rate-limiting-bucket4j.md` — the design + known limitations.
- `conventions/spring-security-filter-order-anchor.md` — anchor filters on a standard filter.
- `conventions/second-springboottest-context-shared-h2.md` — give a divergent @SpringBootTest its own H2 DB.

## Next steps

1. Commit `#26` changes + memory (memory gate needs `.claude/memory/`). gh is at
   `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe` — NOT on PATH; prepend it.
2. Push `feature/issue-26-rate-limiting`.
3. Open PR with `create-pr` (`Closes #26`); note deployment caveat
   (`server.forward-headers-strategy=framework` behind a proxy) + the two follow-ups.
4. `.claude/scripts/issue-board.sh ready 26` after PR opens.

**Parked / cross-branch:**
- **Issue #24** (hash SDK API keys) — done, PR **#40** open, on `feature/issue-24-hash-sdk-api-keys`;
  board move to *Ready For Testing* (`issue-board.sh ready 24`) still PENDING (user paused it), and
  the `last_used_at` throttled-vs-every-request question is unanswered. #24 holds decision 0008 +
  conventions `liquibase-postgres-only-migrations-on-h2`, `sdk-eval-key-column-h2-500`.
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10` pending.
- Issue #17 (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Uncommitted `docs/architecture.md` — unrelated; land or discard separately.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.
- Follow-up (#26): per-IP SDK limit for invalid keys; make `feature_flags.key` H2-safe (#24).
- Raise `jacoco.line.coverage` above 0.00.
