---
name: 0028-flag-hygiene-stale-detection-expiry
description: issue #37 — throttled last-evaluated tracking on the SDK read path (REQUIRES_NEW is mandatory, not stylistic), expiry that only reports, and a per-(flag,environment) hygiene report
metadata:
  type: decision
---

# 0028 — Flag hygiene: stale detection + expiry (issue #37)

## The trap: writing from a read-only transaction

The SDK evaluation path is `@Transactional(readOnly = true)`. A usage write that joins that
transaction is **an UPDATE inside a read-only transaction — PostgreSQL rejects it outright, and H2
lets it through**. Default propagation would therefore pass the entire test suite and fail in
production.

`FlagEnvironmentStateRepository.touchLastEvaluatedAt*` declares
`@Transactional(propagation = REQUIRES_NEW)`. `FlagHygieneIntegrationTest.evaluationPersistsLastEvaluatedAt`
asserts the timestamp is actually persisted through a real `evaluationService.getAllFlags(...)` call,
rather than trusting the annotation. Precedent for REQUIRES_NEW in this repo:
[[0017-refresh-token-family-revoke-transaction-semantics]] — and note its warning does **not** apply
here, because the outer transaction is a read holding no row locks, so there is no self-deadlock.

## Bulk update, not an entity setter — protects `updated_at`

The touch is a JPQL bulk UPDATE, which bypasses Hibernate's lifecycle and so does **not** fire
`@UpdateTimestamp`. That is the point: setting `lastEvaluatedAt` through the managed entity would
bump `updated_at` on every SDK read and destroy its meaning ("when was this flag's configuration last
changed"). Asserted by `usageWriteDoesNotBumpUpdatedAt`.

## Two-layer throttle (the AC's performance requirement)

1. **Caffeine expiring set** keyed by `env:<id>` / `flag:<id>:<env>`, `expireAfterWrite` = the
   throttle window. This skips the DB round-trip *entirely* — 1,000 evaluations in a window cost one
   UPDATE, asserted directly.
2. **Threshold guard inside the UPDATE** (`lastEvaluatedAt IS NULL OR < :threshold`) — race-safe
   across instances, and an instance with a cold cache still cannot exceed the window.

Same shape as `ApiKeyAuthenticationFilter` → `EnvironmentRepository.touchLastUsedAt`. Cache is
bounded (10k) so it cannot leak; eviction only costs one extra no-op UPDATE. Failures are swallowed
and **not** cached, so the next request retries instead of skipping the whole window.

`GET /sdk/flags` stamps the whole environment in **one** statement, so cost does not scale with flag
count.

## Cross-PR hazard with the evaluation cache (#30 / PR #53)

The tracker is called from the evaluation path **outside** any cache-load function. If it were inside
the cache-miss loader, a cache hit would skip it and the hottest flags would be reported stale — the
report would be confidently wrong in exactly the case that matters. Stated in the tracker's Javadoc
because #53 is still open and will touch this method.

## Reporting decisions

- **Expiry reports, never auto-disables.** Flipping a flag off because a date passed is an
  unannounced production behaviour change — the precise thing a flag platform exists to prevent. v1
  surfaces it; a human decides.
- **A row is a (flag, environment) pair, not a flag.** Staleness is inherently per-environment
  ("unused in prod" ≠ "unused in dev", and only the first justifies deletion). Expiry is flag-level,
  so an expired flag yields one row per environment — deliberate, because each row carries `enabled`,
  which surfaces the case that matters: *an expired flag still switched on in production*.
- **A never-evaluated flag is only stale once the flag itself is older than the cutoff.** Otherwise
  every flag created in the last minute is reported stale on sight — noise, not signal. The rule is
  duplicated in the JPQL predicate and in `isStale`, and a test asserts the two agree (a row returned
  by the STALE filter that then reports `stale=false` would be an obvious contradiction).
- Three separate queries rather than one with boolean toggles, so filtering happens **in SQL** and the
  endpoint stays paginable per ADR-0003.
- One `now` per page, so filtering and per-row classification cannot disagree.

## Gotchas hit

- Any `@SpringBootTest` that inserts a `FeatureFlag` needs `NON_KEYWORDS=KEY,VALUE` in the H2 URL —
  `key` is reserved in H2 2.x and the insert fails with a bare syntax error. Cost one run; see
  [[springboot4-jpa-test-quirks]].
- `FlagEvaluationTracker` is a singleton with internal state, so its cache persists **across test
  methods** in one context. The integration test sets the throttle to `1ms` and covers throttling in a
  separate unit test rather than fighting shared state.

## Numbering

Migration **013**: develop now tops out at 012 (webhooks, PR #62, merged), so 013 is the next
free number. ⚠️ The in-flight ABAC branch (PR #87) also claims 013–017 and will have to shift to
014–018 if this PR lands first — whichever merges second renumbers.

Decision **0028**: originally written as 0023, but develop took 0023 (Trivy pin) while this branch
was open, and 0024–0027 went to the Sonar/CD/401/webhooks work. Renumbered at the merge; see
`conventions/sequential-ids-collide-across-long-lived-branches`.

No ADR: the decisions here are localized, unlike ADR-0005's cross-cutting crypto/SSRF contract.
