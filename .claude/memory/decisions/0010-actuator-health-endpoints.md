---
name: 0010-actuator-health-endpoints
description: issue #25 — Spring Boot Actuator; only health+info exposed, only /actuator/health/** anonymous, readiness includes db, show-details=never, Dockerfile HEALTHCHECK
metadata:
  type: decision
---

# Actuator health / liveness / readiness endpoints (issue #25)

**Decided:** add `spring-boot-starter-actuator` for ops health probes (load balancers, k8s,
docker HEALTHCHECK).

## Key choices & rationale

- **Expose only `health,info`** (`management.endpoints.web.exposure.include=health,info`). Every
  other actuator endpoint (`env`, `beans`, `configprops`, `heapdump`, `threaddump`, `loggers`,
  `mappings`) stays unexposed (404).
- **Anonymous access limited to `/actuator/health/**`.** Added a single `permitAll` rule to the
  admin chain in `SecurityConfig` (before the existing rules); the SDK chain's
  `securityMatcher("/api/v1/sdk/**")` never matches actuator. `/actuator/info` and the `/actuator`
  root fall through to `anyRequest().authenticated()` → 403 anonymously (they exist but aren't
  anonymous). Probes must be anonymous for LBs/k8s; nothing else should be.
- **`probes.enabled=true`** forces `/actuator/health/liveness` + `/readiness` on even outside k8s.
  **Readiness = `readinessState,db`** (reflects DB connectivity → 503 when DB down); **liveness
  stays process-only** (must NOT depend on DB, or a DB blip would kill the pod).
- **`show-details=never`** so anonymous health calls return only `{"status":...}` — no DB URL,
  versions, or component details leak. (Security review confirmed no leakage.)
- **`management.info.env.enabled=true` is safe** — it exposes only `info.*` properties (here static
  `info.app.name/description`), NOT arbitrary env vars/secrets. And `/actuator/info` is auth-gated
  anyway.
- **Dockerfile:** `HEALTHCHECK` hits `/actuator/health/readiness` via BusyBox `wget` (already in the
  alpine base, no extra package). Also fixed a pre-existing `EXPOSE 8080` → **8081** mismatch
  (`server.port=8081`).

## Test

`SecurityChainIntegrationTest`: `/actuator/health` + `/liveness` + `/readiness` → 200 anonymously;
`/actuator/info` → 403 anonymously (locked). 54/54 pass.

Touches the two-chain security design — see [[0005-issue-workflow-board-and-memory-gate]] context
and the SDK/Admin chain split in CLAUDE.md.
