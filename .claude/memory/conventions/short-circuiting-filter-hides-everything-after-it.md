---
name: short-circuiting-filter-hides-everything-after-it
description: a servlet filter that writes a response and returns without calling doFilter makes every filter ordered after it unreachable for that request — so anything meant to observe failures must be ordered BEFORE it
metadata:
  type: convention
---

# A short-circuiting filter hides the failure from everything after it

`ApiKeyAuthenticationFilter` and `JwtAuthenticationFilter` both answer a bad credential by
writing a 401 and **returning without calling `filterChain.doFilter`**. That is correct —
but it means every filter ordered *after* them never runs for exactly the requests that
failed authentication.

This is how the SDK chain ended up with rate limiting that only throttled *successful*
callers: `SdkRateLimitFilter` sat after the auth filter, so anonymous key probing — the
traffic a limiter exists to stop — was invisible to it. The admin chain had it right by
accident of intent: `AuthRateLimitFilter` is added *before* `jwtFilter` precisely so
unauthenticated traffic is capped.

**Rule:** when adding a filter that must observe *failed* requests — rate limiting, abuse
metrics, IP banning, anomaly logging — order it **before** the authenticating filter, and
key it on something available pre-authentication (`getRemoteAddr()`, not a principal). A
post-auth filter can only ever measure traffic that already succeeded.

Two related traps seen in the same change:

- A post-auth filter's `resolveKey` returning `null` for "no principal" looks like a safe
  default but silently means *no limit at all*. Confirm whether `null` means skip.
- Both filters now run for an authenticated SDK request (per-IP, then per-environment).
  That is intended, but it means the pre-auth default must be the **looser** of the two,
  or it becomes the effective limit and the per-key one never bites.

See [[0036-sdk-api-key-auth-hardening]] and
[[spring-security-filter-order-anchor]] for how to order it without anchoring on a custom
filter class.
