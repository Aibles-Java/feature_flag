# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #32** (refresh tokens with short-lived access tokens) on branch
`feature/issue-32-refresh-tokens` (→ `develop`). **All 7 plan tasks implemented and
committed; not yet pushed. No PR yet.** 11 commits ahead of `origin/develop`.

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
(+ new `refreshToken`, `expiresIn`). Documented in README "Ops migration" block. Must headline
the PR body.

**Also cleared a long-standing repo issue:** the `docs/ARCHITECTURE.md` vs `docs/architecture.md`
case collision (commit `cba9edb`) — detailed v1.0 doc restored at non-colliding
`docs/architecture-design-v1.md`; lowercase stays the live doc. Removes a HANDOFF follow-up.

## Context to Load

- `decisions/0017-refresh-token-family-revoke-transaction-semantics.md` — the two non-obvious
  transaction bugs and why the fixes look the way they do. Read before touching
  `RefreshTokenServiceImpl` / `RefreshTokenFamilyRevoker`.
- `conventions/second-springboottest-context-shared-h2.md` — the shared-context H2 datasource
  convention the new integration tests follow.

## Next steps

1. **Push** `feature/issue-32-refresh-tokens` (normal push; memory gate is now satisfied —
   this file + MEMORY.md + decision 0017 are in the working tree, stage & commit them with the
   push). Enable the backstop once per clone: `git config core.hooksPath .githooks`.
2. **Open the PR** via the `create-pr` skill. Headline the BREAKING `token`→`accessToken` change.
   Note in the body: 3 security findings reviewed & dismissed, deadlock fix `433266f`, prod
   inherits TTLs by choice.
3. Move the board card to *Ready For Testing*: `.claude/scripts/issue-board.sh ready 32`
   (remember the gh-cli off-PATH prefix — see MEMORY.md).

## Backlog notes surfaced this session (non-blocking, not in scope for #32)
- Absolute session cap for refresh families (`family_created_at` + 30–90d) and a `@Scheduled`
  `deleteByExpiresAtBefore` cleanup — the design spec deliberately scoped both OUT as v1 non-goals.
- Uniform client-facing refresh error message + move the specific reason to the server log
  (hardening; the `requestId` plumbing already supports the split).
- No admin "disable user" endpoint exists yet, so the disabled-account refresh path is only
  reachable via direct DB change today.
