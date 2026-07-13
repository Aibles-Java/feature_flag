# 0012 — Micrometer + Prometheus metrics (issue #29)

**Date:** 2026-07-09
**Branch:** `feature/issue-29-micrometer-prometheus` (off `develop`)

## What was decided

Added observability metrics, scrapable at `/actuator/prometheus`.

- **Deps:** `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (runtime).
  Actuator is added **here**, not inherited from #25 (issue #29 says "Depends on: Actuator
  issue", but #25/PR #42 was still OPEN, not on `develop`). Self-contained so #29 is
  independent — when #25 merges there's a small pom/`management.*`/security overlap to
  reconcile, kept intentionally.
- **Custom meters** in `metrics/FeatureFlagMetrics.java` (a `@Component` façade over
  `MeterRegistry`), **all tags bounded**:
  - `ff_evaluations_total{environment=<env UUID>}` + `ff_evaluation_duration_seconds` timer
    — wrap `EvaluationServiceImpl.getAllFlags`/`getFlag` via `recordEvaluation(envId, supplier)`.
    Kept a separate counter AND timer because the AC names `ff_evaluations_total` explicitly
    (the timer already emits a `_count`, so it's mild redundancy on purpose).
  - `ff_flag_changes_total{change=created|updated|archived|unarchived|state_updated}` — in
    `FeatureFlagServiceImpl` mutations.
  - `ff_auth_failures_total{chain=sdk|admin,reason=...}` — in `ApiKeyAuthenticationFilter`
    (missing_key/invalid_key) and `JwtAuthenticationFilter` (invalid_token/unknown_subject).
  - Bounded meters are **eagerly registered at zero** in the constructor so `rate()` sees the
    0→1 edge. Per-environment evaluation meters can't be (env id unknown until traffic).
- **Security:** new `@Order(0)` `managementFilterChain` with `securityMatcher("/actuator/**")`.
  `health` + `health/**` are `permitAll`; everything else (notably `prometheus`, `info`)
  needs HTTP Basic with role `METRICS`. The scraper account is a **local** in-memory user
  (never a global bean → can't authenticate the app/JWT chains), attached via a local
  `DaoAuthenticationProvider`.
- **Config:** expose only `health,info,prometheus`; `health.show-details=never`;
  `management.metrics.tags.application=${spring.application.name}`; `web.server.max-uri-tags=100`.
  Credential `app.metrics.username=metrics`, `app.metrics.password=${APP_METRICS_PASSWORD:}`.

## Security finding fixed during review (see [[actuator-management-chain-boot41]])

Default `app.metrics.password` is **blank**. `NoOpPasswordEncoder` matches empty-vs-empty, so a
blank secret would let `metrics:` (empty password) authenticate → **auth bypass** on the metrics
endpoint. Fix: when the password is blank the account is built `.disabled(true)` → can never
authenticate; endpoint stays closed until `APP_METRICS_PASSWORD` is set. Regression test
`PrometheusBlankPasswordIntegrationTest`.

## Tests

`FeatureFlagMetricsTest` (unit, `SimpleMeterRegistry`), `PrometheusEndpointIntegrationTest`
(401 no-creds / 401 wrong-creds / 200+`ff_`+`jvm_` with creds / health public),
`PrometheusBlankPasswordIntegrationTest` (own H2 db name — 2nd context). Full suite 172/172.
The 4 unit tests that build the touched services/filters directly now pass a real
`FeatureFlagMetrics(new SimpleMeterRegistry())` — a Mockito mock would swallow the
`recordEvaluation` supplier and break `EvaluationServiceImplTest`.
