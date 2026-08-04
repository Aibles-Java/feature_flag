# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #32** (refresh tokens with short-lived access tokens) on branch
`feature/issue-32-refresh-tokens` (→ `develop`). **PR #59 open;** just merged latest
`develop` in to resolve conflicts (only the two memory-index files clashed).

- `./mvnw verify` green: **239 tests, 0 failures, Spotless clean, JaCoCo floor met.**
- Full flow works end-to-end over the real security chain (`AuthControllerIntegrationTest`):
  login issues an access JWT (15min) + opaque refresh token (14d, SHA-256 at rest);
  `POST /api/v1/auth/refresh` rotates; reusing a rotated token revokes the whole family;
  `POST /api/v1/auth/logout` revokes the family and is idempotent (always 204).
- Security review run (skill's 3-step fan-out): 3 findings raised, **all filtered below the
  ≥8 confidence bar** by adversarial re-check. The one real defect behind finding 1 (a
  PostgreSQL self-deadlock, not an auth bypass) was fixed in commit `433266f`.

**Deviations from the plan (all in commit messages):** `InvalidRefreshTokenException` → 401
instead of flipping the global `UnauthorizedException`→403 mapping; `@SpringBootTest` (not the
plan's `@DataJpaTest`, which the repo never uses); prod inherits the TTLs (no env override —
"no defaults in prod" is a secrets rule, TTLs aren't secrets).

**⚠️ BREAKING API change:** login response field `token` → `accessToken`, `type` → `tokenType`
(+ new `refreshToken`, `expiresIn`). Documented in README "Ops migration" block. Headlines the
PR body.

**Also cleared a long-standing repo issue:** the `docs/ARCHITECTURE.md` vs `docs/architecture.md`
case collision (commit `cba9edb`) — detailed v1.0 doc restored at non-colliding
`docs/architecture-design-v1.md`; lowercase stays the live doc.

**Already on `develop` (issue #33, pagination):** `PageResponse<T>` envelope + `PaginationConfig`
(max-100 clamp); all 6 admin list endpoints paginated; ADR-0003. See
`decisions/0017-pagination-admin-list-endpoints.md`. ⚠️ Both #32 and #33 shipped a decision
file numbered **0017** — renumber one when convenient.

## Follow-up surfaced on develop (do NOT lose)
- **Bug #52 root cause identified** (`GET /organisations/{id}/members` → 500): the code-review of #33
  found `OrganizationServiceImpl.listMembers`/`toMemberResponse` reads lazy `getUser().getEmail()`
  outside a transaction (`open-in-view=false`, no `@Transactional`) → `LazyInitializationException`.
  **Pre-existing** (not caused by #33), so left for #52. Fix: `@Transactional(readOnly=true)` on the
  read path (or `JOIN FETCH om.user`) + an integration test that actually hits the endpoint on a real
  DB (mocked service/controller tests can't catch it). #52 also reports `register returns 201-empty`
  — separate, needs its own look.

## Context to Load

- `decisions/0017-refresh-token-family-revoke-transaction-semantics.md` — the two non-obvious
  transaction bugs and why the fixes look the way they do. Read before touching
  `RefreshTokenServiceImpl` / `RefreshTokenFamilyRevoker`.
- `conventions/second-springboottest-context-shared-h2.md` — the shared-context H2 datasource
  convention the new integration tests follow.
- `decisions/0017-pagination-admin-list-endpoints.md` + `docs/adr/ADR-0003-pagination-strategy.md`.

## Next steps (issue #32 / PR #59)
1. Push the conflict-resolution merge commit on `feature/issue-32-refresh-tokens` so PR #59
   goes mergeable again. **gh is at `C:\Users\ACER\AppData\Local\gh-cli\bin` (not on PATH)** —
   prepend it; see `~/.claude/projects/.../memory/gh-cli-off-path-location.md`.
2. PR #59 body already headlines the BREAKING `token`→`accessToken` change, the 3 dismissed
   security findings, the deadlock fix `433266f`, and prod inheriting TTLs by choice.
3. Board card *Ready For Testing*: `.claude/scripts/issue-board.sh ready 32` (gh off-PATH prefix).

## Backlog notes from the #32 session (non-blocking, not in scope for #32)
- Absolute session cap for refresh families (`family_created_at` + 30–90d) and a `@Scheduled`
  `deleteByExpiresAtBefore` cleanup — the design spec deliberately scoped both OUT as v1 non-goals.
- No admin "disable user" endpoint exists yet, so the disabled-account refresh path is only
  reachable via direct DB change today.

## Known repo issue (pre-existing)
- **`docs/` case collision**: `docs/ARCHITECTURE.md` vs `docs/architecture.md` (differ only by case)
  → git perpetually reports one modified on this Windows FS. Kept out of every commit. Fix on a
  case-sensitive box by deleting one path.

## Follow-ups (carried over)
- **#28** JSON logging — PR **#54** open (mergeable), board Ready For Testing.
- **#31** audit log — depends on #33's paginated read endpoint; JSONB-on-H2 risk.
- **Codegraph #48/#49/#50** on board; #48 (Tier-1 ArchUnit) next greenfield pick.
- Two `decisions/0012-*` files still collide, and now two `0017-*` files — renumber later.
- **#25:** Dockerfile HEALTHCHECK readiness→liveness? DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
