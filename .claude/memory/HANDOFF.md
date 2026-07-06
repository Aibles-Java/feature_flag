# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #25** (Actuator health/liveness/readiness) on branch `feature/issue-25-actuator-health`
(→ `develop`). Implementation complete + verified — **not yet committed/pushed**.

Done:
- `pom.xml`: `spring-boot-starter-actuator`.
- `application.properties`: expose `health,info`; `probes.enabled=true`; readiness =
  `readinessState,db`; `show-details=never`; static `info.app.*`.
- `SecurityConfig`: `permitAll` for `/actuator/health/**` in the admin chain; everything else
  stays `authenticated()`.
- `Dockerfile`: `HEALTHCHECK` on `/actuator/health/readiness` (BusyBox wget); `EXPOSE 8080→8081`.
- `SecurityChainIntegrationTest`: health/liveness/readiness → 200 anonymous; `/actuator/info` →
  403. **54/54 pass.** Security review **clean** (info.env only exposes info.*, no secret leak).

Decision **0010** (0009 is held by #26 on its unmerged branch).

## Context to Load

- `decisions/0010-actuator-health-endpoints.md` — exposure + permit rules + probe/readiness design.

## Next steps

1. Commit `#25` changes + memory (memory gate). gh at `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe`
   (NOT on PATH; prepend it).
2. Push `feature/issue-25-actuator-health`.
3. Open PR with `create-pr` (`Closes #25`).
4. `.claude/scripts/issue-board.sh ready 25` after PR opens.

**Cross-branch / open PRs:**
- **#24** (hash API keys) — **MERGED** to develop (PR #40 closed).
- **#26** (rate limiting) — PR **#41** OPEN, mergeable + CI green (branch merged develop in to
  resolve conflict; test fixed for hashed API). Holds decision 0009 + conventions
  `spring-security-filter-order-anchor`, `second-springboottest-context-shared-h2`. Card *Ready For Testing*.
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10` pending.
- Issue #17 (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Uncommitted `docs/architecture.md` — unrelated; land or discard separately.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.
- Follow-ups: #26 per-IP SDK limit for invalid keys; make `feature_flags.key` H2-safe (#24);
  raise `jacoco.line.coverage` above 0.00.

**Note on #25 vs #26:** both touch `SecurityConfig` — when #26 (PR #41) and #25 both merge to
develop, expect a small conflict in `SecurityConfig.java` (the permitAll additions vs the
rate-limit filter wiring). Resolve by keeping both.
