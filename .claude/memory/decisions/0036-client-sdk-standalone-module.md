# 0036 — Client SDK as a standalone Maven module, matching the live server contract

**Date:** 2026-09-29
**Status:** In progress (feature branch `feature/client-sdk`, not yet merged)
**Feature:** `client-sdk` (SDLC phase P2 design → P4 build; Gate G1 pending human sign-off)

## What was decided

Build a **Feature Flag Client SDK** that external backend apps embed in-process to evaluate
flags, WITHOUT modifying any of this app's server code. The SDK lives in a **standalone Maven
module `feature-flag-sdk/`** — NOT a submodule of the root pom.

- The root `pom.xml` is a Spring Boot application pom (parent = spring-boot-starter-parent).
  Converting it to a multi-module aggregator risked breaking the app's Liquibase/Spring context
  and its coverage ratchet, so the SDK is a **self-contained sibling module** built explicitly
  with `./mvnw -f feature-flag-sdk/pom.xml verify` (NOT `-pl feature-flag-sdk`, which fails
  because the root is not an aggregator — this bit us twice, in code and in CI).
- Only added runtime deps: **Jackson databind** (JSON) + SLF4J API. Zero Spring, zero Lombok.
  HTTP via JDK `java.net.http.HttpClient` (ADR-SDK-001). Cache via `ConcurrentHashMap` +
  `ScheduledExecutorService` (ADR-SDK-002).
- Package: `org.aibles.feature_flag.sdk` (public: `FlagClient`, `FlagClientBuilder`,
  `FlagValueType`, `DiagnosticsSnapshot`, exceptions); `…sdk.internal[.http]` package-private.

## Key contract alignment (see also convention `sdk-must-match-live-server-contract.md`)

The SDK matches the LIVE server (`controller/sdk/EvaluationController` + `FlagEvaluationResponse`),
NOT the P2 design where they diverged:
- `identifier` is a **query param** `?identifier=...` (server reads `@RequestParam`), NOT the
  `X-Flag-Identifier` header the LLD §6.3 mandated. Moving to a header is deferred to a later
  phase requiring a coordinated server change; documented in `feature-flag-sdk/README.md`
  "Known deviation" and as a DE-07 residual (identifier can land in server access logs).
- Auth header `X-Environment-Key`. Response shape `{flagKey, enabled, value(nullable),
  valueType, rolloutPercent}`.

## Core behavioural contracts (enforced + tested)

- **Never throws to the caller on a non-401 failure** — cache-first, serve-stale, then caller
  default. Only `InvalidApiKeyException` (401) and `FlagTypeMismatchException` (genuine type
  mismatch on an enabled flag) propagate. Malformed/oversize values DEGRADE (WARN flag-key-only
  + default), they do NOT throw (ADR-SDK-003).
- **No secret/PII/value leak**: apiKey header-only (never logged/serialized/toString); identifier
  never logged; raw flag value never in any exception message or log (only flag key).
- **TLS 1.2+ pinned** via SSLParameters, no `trustAllCerts`, `http://` rejected at build.
- **Rollout fail-closed** for CONFIDENTIAL-escalated flags (name matches
  card|payment|fraud|kyc|aml|security|pci) when identifier omitted — return caller default, not
  the server's fully-on value (ADR-SDK-004 E2). Applied on ALL resolution paths incl. the
  single-fetch fallback.
- **DoS bounds**: HTTP body capped (4 MB) before Jackson parse on BOTH fetchOne and fetchAll;
  fetchAll list capped (5000); retry bounded + jitter, no-retry on 401/403/404; cache maxEntries
  + maxStaleSeconds.

## Process notes (how it was built)

- Full SDLC chapter-forge loop: P2 design (HLD/LLD/7 ADR/OpenAPI/threat-model/security-review/
  walkthrough) → P4 TDD build → **2 rounds of independent code+security review** (SoD: reviewers
  ≠ author) → fix all CRITICAL/HIGH → 1 final re-review. Every green claim was independently
  re-verified by the architect (not trusted from the agent).
- `dev-executor` stalled (watchdog, not a code error) 3x right before the final verify/commit;
  the architect took over each time to fix compile/test residue and commit.
- CI: added `test-sdk` job (`verify`, no -DskipTests) + `sca-sdk` job (Trivy fs, HIGH/CRITICAL,
  ignore-unfixed:false); `publish` gates on both.

## Result

`feature-flag-sdk/`: 136 tests green, coverage ~86% (floor 80%), Spotless clean. NO app code
touched (every changed file under `feature-flag-sdk/` or `.github/workflows/workflow.yml`).
Branch `feature/client-sdk`, 5 commits, not yet pushed/merged (SoD — human merges).

## Alternatives considered

- Multi-module aggregator root pom — rejected (risk to app build + coverage ratchet).
- Header transport for identifier now — rejected for v1 (would break rollout against live server;
  needs coordinated server change).
- Caffeine cache / OkHttp — rejected (extra deps; ADR-SDK-001/002).
