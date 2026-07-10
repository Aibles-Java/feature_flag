# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #29** (Micrometer + Prometheus metrics) on branch
`feature/issue-29-micrometer-prometheus` (→ `develop`). Code implemented,
security-reviewed (1 finding found + fixed). PR **#44** open; addressed review
(trinhvandat): fixed the `FeatureFlagMetrics` Javadoc lazy/eager contradiction and
documented the third `@Order(0)` security chain in CLAUDE.md + docs/architecture.md.

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
1. Build + test: `./mvnw spotless:apply` then `./mvnw test` — confirm green before committing
   the merge (the working tree still has un-spotless'd #29 files after taking `--ours`).
2. Commit the merge (stage explicit paths — NOT `docs/ARCHITECTURE.md`, the pre-existing
   unrelated rewrite still sitting uncommitted). Then push (memory gate satisfied by this file).
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
- #29 cardinality (from PR #44 review): `ff_evaluations_total{environment}` is unbounded as
  tenants grow — consider dropping the env tag or a `MeterFilter maximumAllowableTags`.
- Docs case-collision: delete the lowercase `docs/architecture.md` stub on a case-sensitive box.
- Raise `jacoco.line.coverage` above 0.00.
