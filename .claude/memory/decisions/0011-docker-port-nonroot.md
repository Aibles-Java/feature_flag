---
name: 0011-docker-port-nonroot
description: issue #27 — align Docker EXPOSE with server.port=8081, run container as non-root, add an `app` service to docker-compose (no healthcheck until actuator lands)
metadata:
  type: decision
---

# Docker: port alignment + non-root + compose app service (issue #27)

**Context (P0 defect):** `server.port=8081` but `Dockerfile` had `EXPOSE 8080`, docker-compose
had no app service, and the container ran as root. Docs (CLAUDE.md) pointed Swagger at :8080.

## Key choices

- **`EXPOSE 8080` → `8081`** to match `server.port`. Also fixed CLAUDE.md Swagger/api-docs URLs
  to :8081. (No root `README.md` exists; no other port refs besides the `docs/architecture.md`
  case-collision file — see [[windows-docs-case-collision]] — which was left out of scope.)
- **Non-root runtime:** `addgroup -S spring && adduser -S spring -G spring`, `chown` the jar,
  `USER spring`. Defense in depth — a container breakout can't land as root. Verified
  `whoami=spring`, `id`=uid 100.
- **`app` service in docker-compose:** `build: .`, `depends_on: postgres condition:
  service_healthy`, `ports: 8081:8081`, and `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/…`
  (inside the compose network the DB is reached by **service name**, not `localhost`).
- **No container HEALTHCHECK on this branch.** The issue's healthcheck item depends on the
  Actuator endpoint (issue #25), which is NOT on `develop`. Left a comment to add a
  `/actuator/health/readiness` HEALTHCHECK once actuator exists. (#25 separately adds a
  Dockerfile HEALTHCHECK — expect a small overlap/conflict when both land: keep the actuator
  one.)

## Verified end-to-end (real Docker, not just `compose config`)

`docker compose up -d --build` → app logs `Tomcat started on port 8081`; `GET :8081/api-docs` →
200; `:8080` → nothing; `whoami` in the running container → `spring`. Postgres reached via the
`postgres` service name; Liquibase ran clean.

## Numbering

Used **0011**: 0010 is held by issue #25 on its own unmerged branch (`feature/issue-25-actuator-health`),
so this skips 0010 to avoid a collision when #25 merges to develop.
