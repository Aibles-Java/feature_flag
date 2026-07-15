---
name: evaluation-caching
description: Caffeine cache + ETag/304 on SDK evaluation endpoint (issue #30) — design decisions and gotchas
metadata:
  type: project
---

## Decision

Issue #30: P1 performance feature. Added Caffeine in-process evaluation cache + HTTP ETag/304 on `GET /api/v1/sdk/flags`.

### What was built

- `FlagStateSnapshot` record (pre-rollout data holder) — cached by `environmentId`
- `EvaluationCacheService` interface + `EvaluationCacheServiceImpl` (raw `Cache<UUID, List<FlagStateSnapshot>>`)
- `EvaluationCacheProperties` (`app.evaluation-cache.max-size`, `app.evaluation-cache.ttl`)
- `EvaluationCacheConfig` — Caffeine bean + Micrometer gauges + `ShallowEtagHeaderFilter` FilterRegistrationBean
- `EvaluationServiceImpl` — cache-aside via `getOrLoadSnapshots()`; both `getAllFlags` and `getFlag` serve from the same cache
- `FeatureFlagServiceImpl` — evicts all project environments on `create`/`archive`/`unarchive`; evicts single env on `updateState`

### Key design choices

**Cache pre-rollout data, not post-rollout responses.** `FlagStateSnapshot` holds `(flagKey, enabled, value, valueType, rolloutPercent)`. Rollout evaluation (`RolloutEvaluator`) is cheap in-memory and runs per-request on top of the cached snapshot. This means one cache entry per environment serves all `identifier` values — an unbounded per-(env,identifier) cache is avoided.

**Raw Caffeine, not Spring Cache abstraction.** Consistent with `RateLimitService` pattern in the codebase. No `spring-boot-starter-cache` needed — `caffeine` was already in the pom via Bucket4j.

**`ShallowEtagHeaderFilter` as `FilterRegistrationBean` for `/api/v1/sdk/flags` and `/api/v1/sdk/flags/*`.** ETag computation and 304 short-circuit handled entirely at the servlet filter layer. Zero changes to `EvaluationController` — it still returns `List<FlagEvaluationResponse>`. The filter buffers the response body, computes SHA-1 ETag, and returns 304 if `If-None-Match` matches.

**Micrometer gauge-based stats registration** (`recordStats()` + `meterRegistry.gauge(...)`) instead of `CaffeineCacheMetrics` — avoids any version-specific API risk with Spring Boot 4.1's Micrometer version.

**Eviction scope:** `create`/`archive`/`unarchive` affect all environments for a project (these mutations fan out to all `FlagEnvironmentState` rows). `updateState(flagId, envId, ...)` is per-environment → evict only that env. `update` (name/description only) does NOT evict — name and description are not in `FlagEvaluationResponse`.

**Why:** Per the issue, single-instance Caffeine is the right scope for v1. Redis caching is explicitly on the v2 roadmap. The in-process approach mirrors the Bucket4j pattern already in use.

### Files touched

- New: `service/FlagStateSnapshot.java`, `service/EvaluationCacheService.java`, `service/impl/EvaluationCacheServiceImpl.java`, `config/EvaluationCacheProperties.java`, `config/EvaluationCacheConfig.java`
- Modified: `service/impl/EvaluationServiceImpl.java`, `service/impl/FeatureFlagServiceImpl.java`, `resources/application.properties`
- Tests: `EvaluationCacheServiceImplTest`, `EvaluationServiceImplTest` (updated), `EvaluationCacheIntegrationTest`, `FeatureFlagServiceImplTest` (updated)

**How to apply:** When adding other cache layers (e.g., segment evaluation in v2), follow this same pattern: snapshot record → cache service interface → Caffeine impl → evict on each mutation path.
