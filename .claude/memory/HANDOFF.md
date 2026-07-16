# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #31** (append-only audit log) on branch `feature/issue-31-audit-log`, **STACKED on
`feature/issue-33-pagination-admin-list`** (PR #57, still open — #31's read endpoint needs #33's
`PageResponse`). **Implementation complete, verified, code-reviewed (no findings).** About to
commit + push + open PR + move board to Ready For Testing.

`./mvnw verify` green: **221 tests**, Spotless clean, JaCoCo 0.83 met. See
`decisions/0018-audit-log-flag-org-mutations.md`.

What changed: `audit_log` table (migration 010, `${json.type}` jsonb/json), `AuditLog` entity +
`AuditAction`/`AuditEntityType` enums, `AuditLogRepository`, `AuditService` (sync same-tx write +
paginated read), `AuditController` (`GET /organisations/{orgId}/audit-log`, VIEWER+, newest-first),
16 `auditService.record(...)` calls wired into the 4 service impls. 7 new tests + 4 service tests
updated (constructor + project needs `.organization(org)`). `docs/ARCHITECTURE.md` case-collision
kept OUT of the commit.

## Next steps
1. Commit (code + memory) — memory gate. Push. `create-pr` base develop (`Closes #31`). Since the
   branch is stacked, the PR diff will include #33's commits until #33 merges; note this in the PR.
   Then `issue-board.sh ready 31`. **gh at `C:\Users\ACER\AppData\Local\gh-cli\bin` (not on PATH)** — prepend it.
2. **Merge order matters**: merge PR #57 (#33) into develop FIRST, then rebase/merge #31, or the
   stacked commits will look odd. Ideally #33 lands, then #31 rebases onto develop.

## Open PRs this session
- **#28** JSON logging — PR **#54 MERGED** to develop.
- **#33** pagination — PR **#57 OPEN, mergeable**, board Ready For Testing.
- **#31** audit log — this branch, about to open PR.

## Follow-up carried (bug #52)
- `OrganizationServiceImpl.listMembers` lazy-load → 500 (the #52 root cause found during #33 review).
  Pre-existing, still open. Fix in #52: `@Transactional(readOnly=true)` / `JOIN FETCH om.user` + a
  real-DB integration test. #52 also reports `register returns 201-empty` (separate).

## Context to Load
- `decisions/0018-audit-log-flag-org-mutations.md` (this) + `decisions/0017-pagination-…` (#33 dep).

## Known repo issue (pre-existing)
- **`docs/` case collision**: `docs/ARCHITECTURE.md` vs `docs/architecture.md` — git perpetually
  reports one modified on this Windows FS. Kept out of every commit. Delete one on a case-sensitive box.

## Follow-ups (carried over)
- Codegraph #48/#49/#50 on board; #48 (Tier-1 ArchUnit) next greenfield pick.
- Two `decisions/0012-*` files still collide — renumber one later.
- #25 HEALTHCHECK readiness→liveness?; #26 per-IP SDK invalid-key limit / Redis; #24 H2-safe `key`.
