# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #29** (Micrometer + Prometheus metrics) on branch
`feature/issue-29-micrometer-prometheus` (→ `develop`, branched from fresh `develop`;
deliberately does NOT depend on #25's actuator branch — added actuator itself). Code
implemented, security-reviewed (1 finding found + fixed), **full suite 172/172 green**.
About to: `/save-memory` commit → push → open PR → `issue-board.sh ready 29`.

Done:
- `pom.xml`: + `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.
- `metrics/FeatureFlagMetrics.java` (new): façade over `MeterRegistry`; bounded meters
  eager-registered at 0.
- `EvaluationServiceImpl` (count+time evals), `FeatureFlagServiceImpl` (flag-change counter),
  `ApiKeyAuthenticationFilter` + `JwtAuthenticationFilter` (auth-failure counters).
- `SecurityConfig`: new `@Order(0)` management chain — `/actuator/**`, health public, else
  HTTP-Basic `METRICS` role via a **local** in-memory user; blank-secret ⇒ account
  `.disabled(true)` (fixes the auth-bypass finding).
- `application.properties`: expose `health,info,prometheus`; `app.metrics.username/password`
  (password `${APP_METRICS_PASSWORD:}`, blank default).
- Tests: `FeatureFlagMetricsTest`, `PrometheusEndpointIntegrationTest`,
  `PrometheusBlankPasswordIntegrationTest` (own H2 db name); 4 existing unit tests updated to
  pass a real `FeatureFlagMetrics(new SimpleMeterRegistry())`.
- `application-test.properties`: shared `app.metrics.password=test-metrics-secret`.

## Context to Load

- `decisions/0012-micrometer-prometheus-metrics.md` — the design + the security finding.
- `conventions/actuator-management-chain-boot41.md` — Boot 4.1 `EndpointRequest` module move +
  blank-`{noop}`-secret auth bypass.
- `conventions/windows-docs-case-collision.md` — why `docs/architecture.md` shows perpetually
  `M`; stage explicit paths, never `git add -A` (it carried into this branch too).

## Next steps

1. Commit (stage explicit paths — NOT `docs/architecture.md`). Then push
   `feature/issue-29-micrometer-prometheus` (memory gate satisfied by this commit).
   gh at `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe` (NOT on PATH; prepend it).
2. Open PR with `create-pr` (`Closes #29`).
3. `.claude/scripts/issue-board.sh ready 29`.

## Numbering note

On `develop` the latest decision is 0009; **0010** (#25 actuator) and **0011** (#27 docker)
live on their own unmerged branches, **0006** on parked #14. This session took **0012** to
avoid a collision when those branches merge.

**Cross-branch / open work (from prior handoffs — verify before acting):**
- **#25** (actuator health) — PR **#42** OPEN. Overlaps #29 on actuator dep + `management.*` +
  `/actuator/**` security. When both merge: reconcile pom (one actuator dep), keep #25's health
  probes; #29's management-chain auth for prometheus should survive.
- **#27** (docker port/non-root) — branch `feature/issue-27-docker-port-nonroot`, committed
  (`2671a6f`), board says *Ready For Testing* (may already be pushed/PR'd — verify).
- #26 (rate limiting) MERGED (PR #41); #24 (hash keys) MERGED (PR #40).
- #10, #17, #14 — still pending per older handoffs.

**Follow-ups:**
- #29: add `/actuator/health/readiness` HEALTHCHECK to the compose `app` service once #25 merges;
  consider Redis-backed metrics if multi-instance aggregation is needed.
- Docs case-collision: delete the lowercase `docs/architecture.md` stub from a case-sensitive box.
- Raise `jacoco.line.coverage` above 0.00.
