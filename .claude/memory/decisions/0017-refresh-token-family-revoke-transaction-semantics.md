# 0017 — Refresh-token family revoke must commit independently, and run before any row lock

**Issue #32** (refresh tokens, branch `feature/issue-32-refresh-tokens`). Reuse detection
revokes the whole token family and then throws to reject the request. Getting that revoke to
*actually persist* took two fixes the plan didn't anticipate — both invisible to mock-based tests.

## Decision 1: revoke runs in its own `REQUIRES_NEW` transaction, not `noRollbackFor`

`RefreshTokenServiceImpl.rotate()` calls `familyRevoker.revoke(...)` then throws
`InvalidRefreshTokenException` (unchecked). Spring rolls back on unchecked exceptions by default,
so the revoke is undone by the very exception that reports it — **reuse detection becomes a silent
no-op in production.**

- First attempt was `@Transactional(noRollbackFor = ...)`. It fixes only the *innermost*
  transaction. Once `AuthServiceImpl.refresh()` (also `@Transactional`) became the caller,
  `rotate()` joined the outer transaction under `REQUIRED` propagation, the outer rollback rules
  won, and the hole reopened.
- **Fix:** a separate bean `RefreshTokenFamilyRevoker` with
  `@Transactional(propagation = Propagation.REQUIRES_NEW)`. It commits on its own connection
  regardless of what the caller's transaction decides. Must be a **separate bean** — `REQUIRES_NEW`
  is proxy-based, so a self-invocation inside `RefreshTokenServiceImpl` would silently no-op.
- `noRollbackFor` is kept on `rotate()` as a second line of defence for its own writes, but the
  security guarantee lives in the revoker.

**Why:** `noRollbackFor` is too fragile for a security control — *any* `@Transactional` caller
added later silently defeats it. `REQUIRES_NEW` makes the guarantee independent of call context.

## Decision 2: check `user.enabled` BEFORE `consume()`, never revoke while holding a row lock

`consume()` is a `@Modifying` UPDATE that holds a row lock for the rest of the (outer) transaction.
The disabled-account branch originally revoked *after* consuming, and the `REQUIRES_NEW` revoke's
`WHERE family_id = ...` matches that same locked row on a second connection → **self-deadlock**.
The DB deadlock detector can't see it (outer tx is idle-in-transaction, not waiting, so no cycle in
the wait graph) and no `lock_timeout` is configured. On PostgreSQL a disabled-user refresh hangs
forever; H2 never exercises real row locks so the whole test suite stayed green.

**Fix:** the enabled-user check moved ahead of `consume()`, so no row lock is held when its revoke
runs. The other three revoke sites were already safe (they run before any write, or after a 0-row
UPDATE that takes no lock).

## Testing lesson (the through-line)

Mockito `verify(revoke(...))` proves the method was *called*, not *committed* — it cannot see
rollback or lock ordering. Both bugs passed every mock test. The catch was
`RefreshTokenRotationCommitTest`: **deliberately non-`@Transactional`** `@SpringBootTest` so real
commit/rollback boundaries apply, asserting `revokedAt != null` after the rejecting call. For a
security control that fires on the exception path, always add one real-transaction integration test.
See [[second-springboottest-context-shared-h2]] for the shared-context H2 datasource convention
these tests follow.

Related: [[0016-secrets-externalization-fail-fast]] (JWT config split into access/refresh TTLs
lives on the same `JwtProperties`), [[springboot4-jpa-test-quirks]].
