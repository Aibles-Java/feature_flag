---
name: permitall-does-not-skip-servlet-filters
description: issue #25 — permitAll only affects authorization; custom filters on the same chain still run for that path, so a new anonymous path must be self-skipped by every custom filter
metadata:
  type: convention
---

# `permitAll()` does not skip the servlet filters on that chain

`authorizeHttpRequests(... .requestMatchers(P).permitAll())` only tells the **authorization**
step to allow `P` anonymously. Every custom filter added to the same `SecurityFilterChain`
(`addFilterBefore`/`addFilterAfter`) **still runs** for requests to `P`. So when you open a new
anonymous path, you must confirm every custom filter on that chain is a no-op for it.

**Concrete case (issue #25 — `/actuator/health/**` on the admin chain):** the admin chain runs
`AuthRateLimitFilter` and `JwtAuthenticationFilter`. Health probes are safe **only because**:

- `AuthRateLimitFilter.shouldNotFilter()` returns `true` for any path not under
  `/api/v1/auth/` → health probes are never rate-limited (else a 30s k8s/docker probe could
  drain the per-IP auth bucket, capacity 10/min).
- `JwtAuthenticationFilter` no-ops when there is no `Bearer` token → anonymous probes pass
  through cleanly.

If a future filter on the admin chain lacks such a guard, it will silently affect
`/actuator/health/**` even though it is `permitAll`. Verify the guard, don't assume `permitAll`
short-circuits the filter chain.

Related: [[spring-security-filter-order-anchor]], [[0010-actuator-health-endpoints]].
