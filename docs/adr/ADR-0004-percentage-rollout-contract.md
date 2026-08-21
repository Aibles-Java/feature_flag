# ADR-0004: Identifier-Based Percentage Rollout — Evaluation Contract

**Status:** Accepted
**Date:** 2026-08-05

## Context

`FlagEnvironmentState.rolloutPercent` (0–100, default 100) lets a flag be enabled for a fraction of
callers. `util/RolloutEvaluator` buckets a caller deterministically: it hashes `identifier:flagKey`
with MurmurHash3, reduces the hash to a bucket in `[0, 100)`, and includes the caller when
`bucket < rolloutPercent`.

The SDK evaluation endpoints (`GET /api/v1/sdk/flags`, `GET /api/v1/sdk/flags/{flagKey}`) take
`identifier` as an **optional** query parameter. Issue #35 requires settling two questions that the
implementation had answered implicitly but never documented:

1. What does a partial rollout return when the caller supplies **no** identifier?
2. How does rollout interact with the evaluation cache, given the result now varies per caller?

Both matter because an SDK's observable behaviour depends on them, and because the second one is a
correctness constraint on any future cache layer, not just today's.

## Decision

### 1. Hash `identifier:flagKey`, not the identifier alone

Bucketing mixes the flag key in, so the same user is not systematically in the low buckets of every
flag. Without this, a user unlucky enough to land in bucket 3 would be in the first 5% of *every*
partial rollout in the platform — rollouts would be perfectly correlated instead of independent.

The consequence is that `key` immutability (already a hard rule — see CLAUDE.md) is now also a
**bucketing** guarantee: changing a flag's key would re-bucket every caller and reshuffle who is in
the rollout.

### 2. A missing identifier fails **open**

When `identifier` is `null` or blank and `0 < rolloutPercent < 100`, the flag is returned as fully
**on** — the rollout is not applied.

Rationale: there is nothing to bucket, so the only options are to fail open, fail closed, or reject
the request. Failing open means an anonymous caller sees the flag's plain `enabled` state — exactly
how the endpoint behaved before rollout percentages existed — which keeps the SDK response contract
backward compatible for every existing client that does not send an identifier.

**This makes a rollout percentage unsuitable as an access-control mechanism.** Any caller can obtain
the "on" branch by omitting `identifier`. A rollout is a *release* tool (limit blast radius while
shipping), never a *security* tool. Security-sensitive behaviour must be gated by authorization, not
by a rollout percentage.

Precedence order in `evaluate(...)`, highest first:

| Condition | Result |
|---|---|
| `enabled == false` | `false` — a disabled flag is off regardless of rollout |
| `rolloutPercent >= 100` | `true` |
| `rolloutPercent <= 0` | `false` |
| identifier `null`/blank | `true` — fail open |
| otherwise | `bucket(identifier, flagKey) < rolloutPercent` |

### 3. Cache pre-rollout state; evaluate per request

The evaluation cache (issue #30) **must** cache the environment's flag state *before* rollout is
applied, and run `RolloutEvaluator` per request on top of it.

Caching the evaluated response instead would be wrong in two ways: the first caller's outcome would
be served to every other caller (breaking per-identifier correctness), and keying the cache by
`(environmentId, identifier)` to avoid that would make it unbounded in the number of end users.

This invariant is what issue #30 / PR #53 implements — `FlagStateSnapshot` holds
`(flagKey, enabled, value, valueType, rolloutPercent)` keyed by `environmentId`, so one cache entry
serves all identifiers. **Any future cache layer must preserve this split.**

HTTP-level caching is unaffected: `identifier` is a query parameter, so it is part of the URL and
therefore part of any HTTP cache key, and the `ShallowEtagHeaderFilter` ETag is computed from the
per-request body.

### 4. Reduce the hash with a sign-bit mask, not `Math.abs`

`toBucket` uses `(hash & Integer.MAX_VALUE) % 100`. `Math.abs(Integer.MIN_VALUE)` returns
`Integer.MIN_VALUE` — still negative — and `Integer.MIN_VALUE % 100` is `-48` in Java, since the
remainder takes the sign of the dividend. A negative bucket compares below *every* rollout
percentage, so the single identifier hashing to `MIN_VALUE` would be permanently included even at a
1% rollout. Regression-tested directly (`RolloutEvaluatorTest.toBucketIsNeverNegative`).

## Consequences

- **Good:** A caller's result is stable across requests, processes and restarts with no per-user
  state stored. Raising a rollout percentage only ever *adds* callers — never flips an
  already-included one back off (property-tested), so a gradual ramp never regresses a user's
  experience.
- **Good:** The SDK response stayed additive — `rolloutPercent` was added to
  `FlagEvaluationResponse`; no field was removed or renamed.
- **Semantic change to note:** `enabled` in the SDK response is now the *effective* value for the
  supplied identifier, not the raw configured state. The raw state remains visible to the admin API
  via `FlagStateResponse`. Clients that treated `enabled` as "the flag's global state" now get a
  per-caller answer — which is the point of the feature, but it is a meaning change rather than a
  shape change.
- **Bad:** Fail-open means a partial rollout is trivially bypassable by omitting `identifier`. Named
  explicitly here and in the OpenAPI parameter description so nobody mistakes it for a gate.
- **Bad:** `Math.abs`-style bugs and hash-distribution regressions are invisible in ordinary
  functional tests. Mitigated by asserting bucket range, determinism, monotonicity and a chi-square
  uniformity check over 10,000 identifiers.

## Alternatives Considered

- **Fail closed when no identifier is supplied.** Rejected: it silently changes behaviour for every
  existing client that does not send an identifier — a flag at 99% would read as *off* for them.
  That is a breaking change to the SDK contract disguised as a default.
- **Reject the request (400) when a flag is on a partial rollout and no identifier is given.**
  Rejected: `GET /sdk/flags` returns *all* flags for an environment, so one flag on a partial rollout
  would fail the whole call. It also couples client correctness to a server-side config change —
  an operator moving a flag to 50% would start breaking clients.
- **Hash the identifier alone.** Rejected — correlates every rollout across flags (see decision 1).
- **Cache the evaluated per-identifier response.** Rejected — see decision 3.
- **`Math.floorMod(hash, 100)`.** A correct alternative to the mask, but the mask states the intent
  (drop the sign bit) more directly and avoids a method call in a per-flag, per-request path.
