# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #27** (fix Docker port + non-root) on branch `feature/issue-27-docker-port-nonroot`
(→ `develop`, branched from fresh `develop` — deliberately does NOT include #25). Code
implemented + **verified end-to-end with real Docker**, committed (`2671a6f`) — **not yet
pushed** (about to push after this memory commit). PR not yet opened.

Done (3 files):
- `Dockerfile`: `EXPOSE 8080→8081`; non-root `spring` user + `USER spring`.
- `docker-compose.yml`: new `app` service (build, `depends_on postgres service_healthy`,
  `8081:8081`, datasource → `postgres:5432` service name). No app healthcheck (actuator/#25
  not on develop — comment left).
- `CLAUDE.md`: Swagger/api-docs URLs :8080 → :8081.

Verified: `docker compose up -d --build` → `Tomcat started on port 8081`, `:8081/api-docs`=200,
`whoami`=spring (uid 100), `:8080`=nothing. (Had to unpublish postgres 5432 in a throwaway
compose override — host already holds 5432.)

## Context to Load

- `decisions/0011-docker-port-nonroot.md` — the choices + verification.
- `conventions/windows-docs-case-collision.md` — why `docs/architecture.md` shows perpetually
  `M`; stage explicit paths, never `git add -A`.

## Next steps

1. Push `feature/issue-27-docker-port-nonroot` (memory gate needs `.claude/memory/` in the
   push — satisfied by this commit). gh at `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe`
   (NOT on PATH; prepend it).
2. Open PR with `create-pr` (`Closes #27`).
3. `.claude/scripts/issue-board.sh ready 27` after PR opens.

**Cross-branch / open PRs:**
- **#25** (actuator health) — PR **#42** OPEN, MERGEABLE + CI green; holds decision 0010 +
  conventions `permitall-does-not-skip-servlet-filters`. Overlaps #27 on Dockerfile (EXPOSE 8081
  + a HEALTHCHECK): when both merge, keep both — the actuator HEALTHCHECK from #25 supersedes the
  compose comment in #27.
- **#26** (rate limiting) — MERGED to develop (PR #41).
- **#24** (hash API keys) — MERGED to develop (PR #40).
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10` pending.
- Issue #17 (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.

**Follow-ups:**
- **Docs case-collision:** delete the lowercase `docs/architecture.md` stub (keep uppercase
  rewrite) to stop the perpetual dirty tree — do it from a case-sensitive box / `git rm --cached`.
- Stashed change `stash@{0}` on branch #25: "docs/architecture.md full rewrite" — now redundant
  (the rewrite already landed on develop as `docs/ARCHITECTURE.md`); drop it.
- **#27:** add `/actuator/health/readiness` HEALTHCHECK to the compose `app` service once #25 merges.
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness`→`liveness`; add DB-down 503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- Raise `jacoco.line.coverage` above 0.00.
