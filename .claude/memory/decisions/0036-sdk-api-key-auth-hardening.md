---
name: 0036-sdk-api-key-auth-hardening
description: three defects on the SDK API-key authentication path — unguarded last_used_at write, unthrottled anonymous key probing, off-schema 401 body — and how each was fixed
metadata:
  type: decision
---

# Hardening the SDK API key authentication path

Found by reviewing the API key implementation (no issue number; user-requested review).
The **key handling itself was clean** — `ApiKeyGenerator` (256-bit `SecureRandom`),
`ApiKeyHasher` (unsalted SHA-256, justified in [[0008-hash-sdk-api-keys-at-rest]]),
storage and rotation. All three defects were in `ApiKeyAuthenticationFilter`, the path
that *consumes* the key.

## 1. A failed `last_used_at` stamp could 500 a valid SDK read

`touchLastUsedAt` ran unguarded on the request thread before `filterChain.doFilter`, so a
`CannotAcquireLockException` / exhausted pool turned an authenticated
`GET /api/v1/sdk/flags` into a 500 — over audit bookkeeping. Now wrapped in
`catch (RuntimeException)` + `log.warn` (env UUID and exception class name only, never the
key or `e.getMessage()`). Same contract `FlagEvaluationTracker.stamp` already applied to
`last_evaluated_at`. Nothing is cached on failure, so the next request retries.

## 2. Anonymous key probing had no ceiling

`SdkRateLimitFilter` buckets by environment id and is anchored *after*
`UsernamePasswordAuthenticationFilter`, i.e. after authentication — so it could never
observe a failed attempt, because `ApiKeyAuthenticationFilter` `return`s a 401 **without
calling `doFilter`**. Its `resolveKey` also returns `null` when unauthenticated (no
limit). Authenticated traffic was capped while probing was not, each attempt still costing
a SHA-256 plus an indexed `findByApiKeyHash`. See
[[short-circuiting-filter-hides-everything-after-it]].

Fixed with a new `SdkIpRateLimitFilter` (scope `SDK_IP`, per `getRemoteAddr()`, default
600/min) **before** the auth filter — the SDK-chain mirror of what `AuthRateLimitFilter`
does for `/api/v1/auth/**`. Default deliberately looser than the 300/min per-key limit so
a NAT'd client fleet is not the first thing to break.

Anchored with `addFilterAfter(..., LogoutFilter.class)`: `LogoutFilter+1` is strictly
before `apiKeyFilter`'s `UPAF−1` slot, and a custom filter cannot be anchored on — see
[[spring-security-filter-order-anchor]]. Two filters at the same anchor+direction would
only have worked by insertion order.

`RateLimitService` was refactored from named `authBuckets`/`sdkBuckets` fields plus a
`scope == AUTH ? … : …` ternary to an `EnumMap` per scope. The ternary silently gave
`SDK_IP` the same buckets *and* limit as `SDK` — caught by the RED test, and the reason
the refactor is part of the fix rather than cosmetic.

## 3. The 401 body was off-schema

`writeUnauthorized` serialised a Spring `ProblemDetail` through a bare `ObjectMapper`,
which cannot produce the right wire shape — the flattening comes from a Jackson mixin on
the MVC mapper, and filters run before MVC. Result: a nested `"properties": null` key,
`"instance": null`, no `requestId`, labelled `application/json`. Now built as an explicit
`LinkedHashMap` and sent as `application/problem+json` with `instance` and `requestId`,
exactly as `ProblemDetailAuthenticationEntryPoint` already did on the admin chain.

**No `WWW-Authenticate` header, deliberately.** RFC 7235 wants one on a 401, but no IANA
scheme exists for a custom API-key header; inventing `ApiKey …` would advertise an auth
flow that does not exist. Documented in the method javadoc so it is not "fixed" later by
someone reading the RFC alone.

## Left open

`feature/api-key-lifecycle` (28 commits ahead of develop, stacked on `feat/api-key-table`
PR #123) rewrites this filter to read from the new `environment_api_key` table and
**carries all three defects forward** — unguarded touch, `APPLICATION_JSON_VALUE` +
`ProblemDetail` via `MAPPER.writeValue`, no pre-auth IP filter. Whichever branch merges
second has to re-apply these fixes by hand; they will conflict on the same file.

Security review of the change found no HIGH/MEDIUM issues, and confirmed the new filter is
not a Spring bean (a bean would have been auto-applied to `/*`, including the admin and
actuator chains).

478 tests (470 → 478).
