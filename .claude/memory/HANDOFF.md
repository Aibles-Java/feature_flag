# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #29** (Micrometer + Prometheus metrics) on branch
`feature/issue-29-micrometer-prometheus` (→ `develop`). Code implemented,
security-reviewed (1 finding found + fixed). PR **#44** open; **all three review
points from trinhvandat now addressed:**
- (#2) Javadoc lazy/eager contradiction — fixed earlier (`aeb5b53`).
- (#3) third `@Order(0)` security chain documented in CLAUDE.md + docs/architecture.md — `aeb5b53`.
- (#1, cardinality — the substantive one) `FeatureFlagMetrics` class Javadoc rewrote the
  "deliberately bounded" claim: the `environment` tag is bounded by TOTAL Environment rows
  across ALL orgs (not per-tenant), meters are never evicted (~4 permanent series/env), and
  `max-uri-tags` does NOT cap custom meters. Kept the tag (issue #29 AC requires
  `ff_evaluations_total{environment}`). Comment-only, compile+spotless green. Commit **`6eb7d28`**.

**Just merged `origin/develop` into this branch again** to clear PR conflicts. develop had
advanced with PR **#51** (codegraph adoption — spec/planning only, no source). The only
conflicts were in `.claude/memory/` (HANDOFF, MEMORY.md, today's session file — both branches
ran `/save-memory` on 2026-07-11); resolved by **union**. No product-code conflicts.

## Next steps
1. **Push** this branch (memory gate satisfied by this file), then reply on PR #44 that all
   three review points are addressed (`6eb7d28` covers cardinality). Confirm PR shows mergeable.
2. `docs/ARCHITECTURE.md` (uppercase) is still modified/uncommitted — a large pre-existing
   −688/+63 rewrite by another author (oanhhkim), unrelated to #29. Left out of every commit on
   purpose. Decide separately whether to land or discard it.

## Context to Load

- `decisions/0012-micrometer-prometheus-metrics.md` — the #29 design + the security finding.
- `conventions/actuator-management-chain-boot41.md` — Boot 4.1 `EndpointRequest` module move +
  blank-`{noop}`-secret auth bypass.
- `decisions/0014-codegraph-adoption.md` — codegraph work now merged to develop (PR #51).

## Numbering note

Filename collision to clean up later: both `decisions/0012-micrometer-prometheus-metrics.md`
(#29) and `decisions/0012-harness-guards-spotless-coverage.md` (develop) claim **0012**.
Distinct filenames so no git conflict, but renumber one on the next `/save-memory`.

## Follow-ups
- **#29 cardinality** (PR #44 review): documented honestly in the Javadoc as of `6eb7d28`. Kept
  the env tag (AC requires it). If tenants ever reach thousands, revisit — drop the env tag or
  add a `MeterFilter maximumAllowableTags`. Not urgent at current scale.
- **Docs case-collision:** delete the lowercase `docs/architecture.md` stub on a case-sensitive box.
- **Raise `jacoco.line.coverage`** above 0.00.

**Parked / cross-branch (from prior sessions):**
- Unrelated `docs/ARCHITECTURE.md` change still uncommitted — land or discard separately.
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`), #17 (`feature/issue-17-estimate-issue-skill`)
  — commit/push/PR/`ready` pending.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.
- **Codegraph (#48/#49/#50)** filed on the Digital banking board; #48 (Track A Tier-1 ArchUnit
  gate) is the next implementation to pick up. See `decisions/0014` + `docs/specs/codegraph-adoption.md`.

**Follow-ups (from earlier work):**
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness` → `liveness`; add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
</content>
