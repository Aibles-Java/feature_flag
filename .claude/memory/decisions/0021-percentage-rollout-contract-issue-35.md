---
name: 0021-percentage-rollout-contract-issue-35
description: issue #35 — the rollout code already existed on develop; the real work was the fail-open contract (ADR-0004), a Math.abs sign bug, and the statistical tests the ACs demanded
metadata:
  type: decision
---

# 0021 — Identifier-based percentage rollout: contract + sign bug (issue #35)

**Context:** The issue reads like a from-scratch feature (add column, add param, wire evaluator),
but **~90% of it was already on `develop`** — landed earlier via `feat/rollout-percent`
(`7daed49` + `cd666ee`) without ever closing the issue. Before writing anything, check what's
actually implemented; see [[stale-issue-scope-verify-before-implementing]].

Already present: migration `008-add-rollout-percent.xml`, `FlagEnvironmentState.rolloutPercent`,
`UpdateFlagStateRequest.rolloutPercent` with `@Min(0) @Max(100)`, `identifier` query param on both
SDK endpoints, `EvaluationServiceImpl` calling `RolloutEvaluator.evaluate`.

Actually missing: the two statistical ACs (no `RolloutEvaluatorTest` existed *at all*), the
contract documentation, and a real bug.

## The bug: `Math.abs` cannot make a hash non-negative

`toBucket` was `Math.abs(hash) % 100`. `Math.abs(Integer.MIN_VALUE)` returns
`Integer.MIN_VALUE` — still negative — and `Integer.MIN_VALUE % 100 == -48` in Java, because the
remainder takes the sign of the **dividend**, not the divisor. A negative bucket compares below
*every* rollout percentage, so the single identifier hashing to `MIN_VALUE` would be permanently
included even at a 1% rollout.

Fixed with `(hash & Integer.MAX_VALUE) % BUCKETS`. `Math.floorMod` would also work; the mask states
the intent (drop the sign bit) and avoids a call in a per-flag, per-request path.

**Testability note:** this is unreachable through the public `evaluate(...)` — you'd have to find a
string whose MurmurHash3 is exactly `MIN_VALUE` (~2³² search). Extracting a package-private
`toBucket(int)` seam makes it a one-line regression test. Same for `bucketFor(id, key)`, which lets
the distribution test assert on buckets directly instead of inferring them from decisions.

## The contract decision: a missing identifier fails OPEN

`identifier` is optional, so a caller can hit a flag on a partial rollout with nothing to bucket.
Chose **fail open** (flag reads fully on) over fail-closed or 400 — full rationale and rejected
alternatives in `docs/adr/ADR-0004-percentage-rollout-contract.md`.

- Fail-closed would silently flip a 99% flag to *off* for every existing client that doesn't send an
  identifier — a breaking SDK change disguised as a default.
- 400 would fail the whole `GET /sdk/flags` call (it returns *all* flags) and couple client
  correctness to a server-side config change.

**Consequence to remember: a rollout percentage is NOT access control** — any caller gets the "on"
branch by omitting `identifier`. Stated in the class Javadoc, the ADR, and the OpenAPI parameter
description.

Also worth knowing: `enabled` in the SDK response is now the *effective* per-identifier value, not
the raw configured state. The field set stayed additive (`rolloutPercent` added, nothing removed), so
the shape is backward compatible — but the **meaning** of `enabled` changed.

## Caching interaction — already satisfied by #30, recorded as an invariant

The issue asks to settle rollout-vs-cache. PR #53 (issue #30, still open) already does the right
thing: it caches `FlagStateSnapshot` (**pre**-rollout: flagKey/enabled/value/valueType/rolloutPercent)
keyed by `environmentId`, and runs `RolloutEvaluator` per request on top. So one cache entry serves
all identifiers. Caching the *evaluated* response would serve caller A's outcome to caller B; keying
by `(envId, identifier)` would make the cache unbounded in end users. ADR-0004 decision 3 records
this as a constraint on any **future** cache layer, not just #53's.

ETag is unaffected — `identifier` is a query param, so it's part of the URL and any HTTP cache key.

## Tests written (the ACs)

`RolloutEvaluatorTest` (24 tests) — determinism across repeats × 5 percentages; **monotonicity**
(raising the percentage never removes an included identifier — the property that makes a gradual ramp
safe); chi-square uniformity over 10,000 identifiers (< 148.2 = 99.9th percentile, 99 dof);
per-percentage included-share within ±2pp; per-flagKey bucket independence (proves `flagKey` is
really mixed in — hashing the identifier alone would correlate every rollout platform-wide); the
`toBucket` sign regression; and the documented edge cases.

All identifiers are **generated, never random**, so a failure is a real regression, not a flake.

Also added: 5 service-level tests pinning the fail-open contract at the API boundary, and the first
tests for the `@Min(0)/@Max(100)` admin validation (it was wired but unproven).
