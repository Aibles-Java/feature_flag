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

**Just merged `origin/develop` into this branch** to clear PR conflicts. develop had
moved on with: repo-wide google-java-format (Spotless), Slack notifications
(`notification/**`, event publishes in `FeatureFlagServiceImpl`), and harness guards.

Conflict resolution taken:
- `FeatureFlagServiceImpl` — kept BOTH develop's Slack `eventPublisher` publishes AND
  #29's `metrics.recordFlagChange(...)`; constructor now has 7 deps (…, eventPublisher, metrics).
- `FeatureFlagServiceImplTest` — kept develop's event-assertion tests; setUp passes both
  `eventPublisher` and a real `FeatureFlagMetrics(new SimpleMeterRegistry())`.
- `SecurityConfig`, both auth filters, `EvaluationServiceImpl`, `EvaluationServiceImplTest`,
  filter tests, `SecurityChainIntegrationTest` — took #29 (`--ours`); develop only reformatted
  them, and #29's `@Order(0)` management chain deliberately supersedes #25's admin-chain
  `/actuator/health/**` rule (integration test asserts `/actuator/info` → 401, not 403).
- `MEMORY.md` — unioned both sets of entries.

## Next steps
1. Push branch (memory gate satisfied by this file), then reply on PR #44 that all three
   review points are addressed (`6eb7d28` covers cardinality).
2. `docs/ARCHITECTURE.md` (uppercase) is still modified/uncommitted — a pre-existing unrelated
   rewrite. Left out of `6eb7d28` on purpose. Decide separately whether to commit or discard it.
3. Confirm PR #44 shows mergeable; ping reviewer.

## Context to Load

- `decisions/0012-micrometer-prometheus-metrics.md` — the #29 design + the security finding.
- `conventions/actuator-management-chain-boot41.md` — Boot 4.1 `EndpointRequest` module move +
  blank-`{noop}`-secret auth bypass.
- `decisions/0013-slack-notifications.md` — develop's Slack feature now on this branch.
- `conventions/spotless-scoping-and-bash32.md` — the google-java-format tooling develop added.

## Numbering note

Filename collision to clean up later: both `decisions/0012-micrometer-prometheus-metrics.md`
(#29) and `decisions/0012-harness-guards-spotless-coverage.md` (develop) claim **0012**.
Distinct filenames so no git conflict, but renumber one on the next `/save-memory`.

**Follow-ups:**
- #29 cardinality (PR #44 review): documented honestly in the Javadoc as of `6eb7d28`. Kept the
  env tag (AC requires it). If tenants ever reach thousands, revisit — drop the env tag or add a
  `MeterFilter maximumAllowableTags`. Not urgent at current scale.
- Docs case-collision: delete the lowercase `docs/architecture.md` stub on a case-sensitive box.
- Raise `jacoco.line.coverage` above 0.00.
