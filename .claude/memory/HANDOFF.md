# Handoff

## Current WIP

Two branches, both committed, tested and **not yet pushed**. Neither has a PR.

**`feature/api-key-hardening`** (2 commits off `develop`)
- `cd3db5b docs:` — syncs `docs/architecture-design-v1.md` §4/§6/§7 with the code. It
  claimed API keys were stored in plaintext (hashed since #24), ~192 bits of entropy (it
  is 256), two filter chains (three since #29), a single 24h JWT (access 15m + refresh
  14d), and pagination as a v2 concern (shipped in #33).
- `ab9f200 fix:` — the three defects in [[0036-sdk-api-key-auth-hardening]].
  New file `security/ratelimit/SdkIpRateLimitFilter.java`; `SecurityConfig`,
  `ApiKeyAuthenticationFilter`, `RateLimitService`, `RateLimitProperties`,
  `application.properties` modified. 478 tests pass, spotless clean, security review
  found no HIGH/MEDIUM.

**`feature/env-create-backfills-flag-states`** (1 commit off `develop`)
- `601e5e1 fix:` — `EnvironmentServiceImpl.create()` now backfills a state row per
  existing flag, plus migration `019` to repair existing data. See
  [[flag-environment-state-invariant-has-two-sides]]. 472 tests pass.

## Context to Load

- `decisions/0036-sdk-api-key-auth-hardening.md`
- `conventions/short-circuiting-filter-hides-everything-after-it.md`
- `conventions/flag-environment-state-invariant-has-two-sides.md`
- `conventions/spring-security-filter-order-anchor.md` (why the new filter anchors on
  `LogoutFilter`)

## Next steps

1. **Push the two branches and open PRs** against `develop`. The user also asked for a PR
   on the pre-existing `feature/api-key-lifecycle` (11 commits unpushed, 28 ahead of
   develop). That branch is stacked on `feat/api-key-table` (PR #123) and
   `docs/api-key-design` (PR #122) — **base its PR on `feat/api-key-table`, not
   `develop`**, or the diff swallows both open PRs.

2. **Resolve the collision before either side merges.** `feature/api-key-lifecycle`
   rewrites `ApiKeyAuthenticationFilter` for the new `environment_api_key` table and still
   has all three defects: unguarded `touchLastUsedAt`, `APPLICATION_JSON_VALUE` +
   `ProblemDetail` through a bare mapper, and no pre-auth IP rate limit. Whichever merges
   second must re-apply the fixes by hand — they conflict on the same file.

3. **Three review findings still open**, all in `EnvironmentServiceImpl`, none started:
   - `create()` accepts `type` and the change window behind `ENV_CREATE` (ADMIN), while
     `update()` gates the same attributes on OWNER-only `ENV_MANAGE_PROTECTION`. An ADMIN
     can create a `PRODUCTION` env with a 1-hour change window, and because
     `productionEnvironments` resolves *every* prod env under the project for
     project-scoped archive, that blocks archive/unarchive project-wide 23 hours a day —
     for OWNERs too.
   - `update()` renames without the `existsByProjectIdAndName` check that `create()` and
     `clone()` both do, so a duplicate name returns 500 (no `DataIntegrityViolationException`
     handler in `GlobalExceptionHandler`) instead of the 409 the create path returns.
   - A change window cannot be cleared once set: `update()` skips null fields and
     `isChangeWindowComplete()` rejects sending one half. Only `start == end` (zero-width,
     treated as unrestricted) neutralises it, which is undiscoverable from the API.

4. Migration `019`'s SQL is **unverified against real data** — it runs on an empty H2 in
   CI, proving only that the syntax is valid. Run it against a local Postgres with a
   project that has flags and a late-created environment before trusting it.
