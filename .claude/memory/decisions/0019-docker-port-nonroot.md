---
name: 0019-docker-port-nonroot
description: issue #27 — align Docker EXPOSE with server.port=8081, run container as non-root, add an `app` service to docker-compose; merge with develop layered USER spring under #25's readiness HEALTHCHECK and forced APP_JWT_SECRET into compose
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
- **No container HEALTHCHECK when first authored** — the Actuator endpoint (issue #25) was not yet
  on `develop`. Superseded at the merge, see below.

## Verified end-to-end (real Docker, not just `compose config`)

`docker compose up -d --build` → app logs `Tomcat started on port 8081`; `GET :8081/api-docs` →
200; `:8080` → nothing; `whoami` in the running container → `spring`. Postgres reached via the
`postgres` service name; Liquibase ran clean.

## Merge with `develop` (PR #43 review finding)

The branch went `CONFLICTING` because #25 (PR #42) and #23 both landed on `develop` and rewrote the
same Dockerfile region. Resolution — **layer, don't replace**:

- Kept develop's `HEALTHCHECK` on `/actuator/health/readiness` and its
  `ENV SPRING_PROFILES_ACTIVE=prod`; added this branch's `spring` user + `USER spring` **above**
  them. Taking either side wholesale silently drops the other half.
- `EXPOSE 8081` is now redundant with develop (same line both sides) — harmless.
- **Non-obvious knock-on:** because the image bakes `SPRING_PROFILES_ACTIVE=prod`, and
  `application-prod.properties` has **no defaults**, the compose `app` service would crash-loop on
  the missing `APP_JWT_SECRET`. Compose now passes
  `APP_JWT_SECRET: ${APP_JWT_SECRET:?…}` so the failure is an up-front compose error naming the
  variable instead of a restart loop, and the datasource reuses the same `${POSTGRES_*}` vars the
  `postgres` service uses (hardcoding `ff_user`/`ff_password` broke if those were overridden in
  `.env`).
- No compose-level `healthcheck:` block — compose inherits the Dockerfile `HEALTHCHECK`.
- Also removed the case-colliding `docs/ARCHITECTURE.md` index entry; develop had already renamed
  it to `docs/architecture-design-v1.md` (see [[windows-docs-case-collision]]). Without this the
  merge cannot even start on Windows: the phantom path is *always* dirty, so
  `git merge` aborts with "local changes would be overwritten".

## Numbering

Authored as **0011** on-branch; renumbered **0019** at the develop merge — 0011 was taken by
`0011-review-pr-skill`, and 0018 by the unmerged issue-34 branch.
