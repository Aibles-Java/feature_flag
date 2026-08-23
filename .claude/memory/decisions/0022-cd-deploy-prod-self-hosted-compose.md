---
name: 0022-cd-deploy-prod-self-hosted-compose
description: CD deploy job — on v* tag, after publish, pull GHCR image and docker compose up on self-hosted
metadata:
  type: decision
---

# CD: deploy prod via Docker Compose on the self-hosted runner

Added a `deploy` job to `.github/workflows/workflow.yml` (NOT a separate `cd.yml`).
Trigger: **`vX.Y.Z` tag push only**, gated on **`needs: publish`** so deploy runs
only after the exact image passed tests + a clean Trivy scan and is already in
GHCR. Chain: `test` (ubuntu) → `publish` (ubuntu) → `deploy` (self-hosted).

**Why same workflow, not a separate cd.yml:** a separate tag-triggered workflow
would fire *concurrently* with `workflow.yml` on the same `v*` push — deploy could run
before publish finished and pull a not-yet-pushed image. `needs:` only works
within one workflow, so the ordering guarantee requires the job live in `workflow.yml`.

**Why self-hosted:** the prod host is on the internal network (same reason
`sonar.yml` is self-hosted, see [[0018-sonarqube-ci-self-hosted-runner]]). Safe
here because the job runs **only on maintainer-pushed tags** — never a fork PR
(the fork-on-self-hosted risk that keeps CI test/publish on `ubuntu-latest`
stays intact; repo is **public**).

**Image ref:** derived from the tag in shell, not `${{ }}` interpolation (inject
via `$GITHUB_REF_NAME`/`$GITHUB_REPOSITORY` env — the safe pattern): `v1.2.3` →
`ghcr.io/<repo-lowercased>:1.2.3`, matching metadata-action's `{{version}}` tag
from the publish job. GHCR path must be lowercased (`tr '[:upper:]' '[:lower:]'`).

**Manual test path (`workflow_dispatch`):** a second entry point deploys an
**already-published** GHCR version without cutting a real tag (`version` input,
e.g. `1.2.3`). On dispatch `publish` is skipped, so deploy's `if:` is
`always() && (event==workflow_dispatch || (tag && needs.publish.result=='success'))`
— `always()` is required or a skipped `publish` would skip deploy too. The input
is untrusted → passed via `env: INPUT_VERSION`, never interpolated into `run:`.
Quirk: the "Run workflow" button only appears once this file is on the **default
branch** (`develop`). Normal pushes to develop/main still skip deploy (tag-only).

**`docker-compose.prod.yml`** (new, distinct from dev `docker-compose.yml` which
`build: .`): app uses `image: ${APP_IMAGE}` (pull, no build); Postgres port is
**NOT** published to the host (only the app on the compose network needs it);
every secret is `${VAR:?...}`-required (no dev fallback — prod profile is baked
into the image). Liquibase runs on app startup (ddl-auto=validate) so migrations
apply during `up` — no separate migration step.

**Deployment gate:** a health step polls `http://localhost:8081/actuator/health/readiness`
for ~150s; on failure it dumps `app` logs and fails the job, so a bad release is
visible immediately. Job-level `concurrency: {group: deploy-prod,
cancel-in-progress: false}` serializes deploys and never kills one mid-`up`.

**Ops prerequisites (must exist before first release):**
- Repo/`production` environment secrets: `APP_JWT_SECRET`, `POSTGRES_DB`,
  `POSTGRES_USER`, `POSTGRES_PASSWORD`.
- GitHub Environment named `production` (enables optional required-reviewer
  approval + a deployment record).
- Self-hosted runner needs `docker compose` v2 and pull access to GHCR (uses the
  workflow `GITHUB_TOKEN` with `packages: read`).
- Deployed via: `git tag vX.Y.Z && git push origin vX.Y.Z`.
