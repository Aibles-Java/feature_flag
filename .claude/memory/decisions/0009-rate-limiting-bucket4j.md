---
name: 0009-rate-limiting-bucket4j
description: issue #26 — Bucket4j in-memory token buckets; per-IP on /auth/**, per-env-id on /sdk/**; 429+Retry-After; Caffeine-evicted buckets
metadata:
  type: decision
---

# Rate limiting with Bucket4j (issue #26)

**Decided:** in-memory token-bucket rate limiting via **Bucket4j** (`com.bucket4j:bucket4j_jdk17-core:8.19.0`),
enforced by two `OncePerRequestFilter`s under `security/ratelimit/`.

## Key choices & rationale

- **Two scopes, two keys.** `AuthRateLimitFilter` throttles `/api/v1/auth/**` **per client IP**
  (brute-force protection; endpoints are `permitAll` so IP is the only stable identifier).
  `SdkRateLimitFilter` throttles `/api/v1/sdk/**` **per `Environment.id`** (read from the
  `ApiKeyAuthenticationToken` principal). Defaults `auth 10/min`, `sdk 300/min`.
- **IP source = `request.getRemoteAddr()` only** — deliberately NOT `X-Forwarded-For`. Trusting
  XFF when the app is directly exposed lets an attacker forge the header to rotate the key and
  bypass the per-IP limit. Behind a trusted proxy, set `server.forward-headers-strategy=framework`.
- **429 emitted inline in the filter** (filters run before MVC, so `@RestControllerAdvice` can't
  apply) as an RFC-7807 `ProblemDetail` matching `GlobalExceptionHandler`'s shape, plus a
  `Retry-After` header (`max(1, nanosToWaitForRefill→seconds)`). Mirrors `ApiKeyAuthenticationFilter`'s
  401 pattern.
- **Buckets stored in a Caffeine cache** (`expireAfterAccess = 2× refill-period`), not a plain
  `ConcurrentHashMap` — the per-IP map is otherwise unbounded (memory leak / DoS vector; both
  reviewers flagged it ~80). Eviction is safe: an idle bucket has refilled to full, so dropping it
  equals a fresh full bucket. Caffeine version is managed by the Spring Boot BOM (no explicit version).
- **Configurable via `app.rate-limit.*`** (`@ConfigurationProperties`, `@EnableConfigurationProperties`
  on `SecurityConfig`); `enabled=true` in main, `enabled=false` in the test profile so unrelated
  tests aren't throttled. The rate-limit integration test re-enables with low caps.

## Filter wiring gotcha

`addFilterBefore/After` must anchor on a **registered standard** filter — see
[[spring-security-filter-order-anchor]]. Both limiters anchor on `UsernamePasswordAuthenticationFilter`:
SDK limiter `addFilterAfter` (runs after `apiKeyFilter` → principal resolved), auth limiter
`addFilterBefore`.

## Known limitations (documented follow-ups, NOT in this PR)

- **Invalid-key SDK traffic is unthrottled.** `ApiKeyAuthenticationFilter` rejects a bad key with
  401 *before* `SdkRateLimitFilter` runs, but each bad request still costs a `findByApiKey` DB
  lookup — the exact "exhaust DB pool" vector the issue names. Per-API-key limiting (the issue's
  scope) can't cover key-less traffic; a per-IP limit on the SDK chain would. Deferred by choice.
- **Per-instance limits.** In-memory buckets aren't shared across replicas; a distributed backend
  (Bucket4j + Redis) is a later upgrade.

Test isolation note: the rate-limit `@SpringBootTest` needs its own H2 DB — see
[[second-springboottest-context-shared-h2]].
