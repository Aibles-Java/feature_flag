# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #33** (pagination on admin list endpoints) on branch
`feature/issue-33-pagination-admin-list` (→ `develop`, branched off latest develop).
**Implementation complete, verified, code-reviewed (no blocking findings).** About to
commit + push + open PR + move board to Ready For Testing.

`./mvnw verify` green: **214 tests**, Spotless clean, JaCoCo 0.83 floor met. See
`decisions/0017-pagination-admin-list-endpoints.md` + `docs/adr/ADR-0003-pagination-strategy.md`.

What changed: `PageResponse<T>` envelope + `PaginationConfig` (max-100 clamp bean); all 6 admin
list endpoints paginated (controller→service `Page<>`→repo); ADR-0003; 9 tests updated + clamp/sort
+ real-H2 repo pagination tests. `docs/ARCHITECTURE.md` case-collision kept OUT of the commit.

## Next steps
1. Commit (code + memory together — memory gate), push, `create-pr` (base develop, `Closes #33`),
   then `issue-board.sh ready 33`. **gh is at `C:\Users\ACER\AppData\Local\gh-cli\bin` (not on PATH)** —
   prepend it; see `~/.claude/projects/.../memory/gh-cli-off-path-location.md`.

## Follow-up surfaced this session (do NOT lose)
- **Bug #52 root cause identified** (`GET /organisations/{id}/members` → 500): the code-review of #33
  found `OrganizationServiceImpl.listMembers`/`toMemberResponse` reads lazy `getUser().getEmail()`
  outside a transaction (`open-in-view=false`, no `@Transactional`) → `LazyInitializationException`.
  **Pre-existing** (not caused by #33), so left for #52. Fix: `@Transactional(readOnly=true)` on the
  read path (or `JOIN FETCH om.user`) + an integration test that actually hits the endpoint on a real
  DB (mocked service/controller tests can't catch it). #52 also reports `register returns 201-empty`
  — separate, needs its own look.

## Context to Load
- `decisions/0017-pagination-admin-list-endpoints.md` + `docs/adr/ADR-0003-pagination-strategy.md`.
- `decisions/0015-structured-json-logging-request-correlation.md` — #28, PR #54 (still open).

## Known repo issue (pre-existing, not #33)
- **`docs/` case collision**: `docs/ARCHITECTURE.md` vs `docs/architecture.md` (differ only by case)
  → git perpetually reports one modified on this Windows FS. Kept out of every commit. Fix on a
  case-sensitive box by deleting one path.

## Follow-ups (carried over)
- **#28** JSON logging — PR **#54** open (mergeable), board Ready For Testing.
- **#31** audit log — depends on #33 (this) for the paginated read endpoint; JSONB-on-H2 risk.
- **Codegraph #48/#49/#50** on board; #48 (Tier-1 ArchUnit) next greenfield pick.
- Two `decisions/0012-*` files still collide — renumber one later.
- **#25:** Dockerfile HEALTHCHECK readiness→liveness? DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
