---
name: 0022-cd-deploy-prod-self-hosted-compose
description: CD deploy job — push to develop (or v* tag / dispatch) after publish, pull immutable sha-tagged GHCR image and docker compose up on self-hosted, with auto-rollback
metadata:
  type: decision
---

# CD: deploy prod via Docker Compose on the self-hosted runner

Added a `deploy` job to `.github/workflows/workflow.yml` (NOT a separate `cd.yml`).
**Primary trigger: push to `develop`** — continuous deploy to the (test) server:
`test` (ubuntu) → `publish` (ubuntu) → `deploy` (self-hosted), all in one push.
Also deploys on a **`vX.Y.Z` tag** and via manual **`workflow_dispatch`**. Every
path is gated on **`needs: publish` succeeding** so deploy only ever rolls out an
image that passed tests + a clean Trivy scan and is in GHCR.

**Workflow is named `CI/CD`** (was `CI`) since it does both. Renaming is safe re:
branch protection — that keys on the **job** check names (`Build & test (Java 21)`,
`Publish Docker image (GHCR)`, `Deploy to server (self-hosted)`), NOT the workflow
name. The one real coupling is the **GitHub Slack app**: `docs/slack-notifications.md`
subscribes with `workflows:{name:"CI/CD"}` (exact-match) — change both together or
Slack silently stops notifying. (The only correct way to split into a real `cd.yml`
would be `workflow_run`, rejected: no PR-checks visibility, CD file always taken
from the default branch, awkward tag path.)

**Why same workflow, not a separate cd.yml:** a separate trigger-based workflow
would fire *concurrently* with `workflow.yml` on the same push — deploy could run
before publish finished and pull a not-yet-pushed image. `needs:` only works
within one workflow, so the ordering guarantee requires the job live in `workflow.yml`.

**Why self-hosted:** the server is on the internal network (same reason
`sonar.yml` is self-hosted, see [[0018-sonarqube-ci-self-hosted-runner]]). Safe
here because the job runs **only on maintainer-pushed branches/tags/dispatch** —
never a fork PR (the fork-on-self-hosted risk that keeps CI test/publish on
`ubuntu-latest` stays intact; repo is **public**).

**Image tags** (metadata-action): `type=ref,event=branch` → the moving
`develop`/`main` tag (still published, for humans); `type=sha` → `sha-<short>`
**immutable tag — this is what deploy actually pulls on a develop push**;
`type=semver` on a `v*` tag; `latest` only on `main`. Deploy resolves the ref in
shell (env-injected, never `${{ }}` interpolation): develop push →
`:sha-${GITHUB_SHA:0:7}` (matches metadata-action's default 7-char `type=sha`),
tag (`GITHUB_REF_TYPE == tag`) → `:1.2.3`, dispatch → the input verbatim (accepts
`1.2.3` OR a `sha-<short>` for a hand rollback). GHCR path lowercased
(`tr '[:upper:]' '[:lower:]'`). **Why sha not the moving `develop` tag:** you
always know exactly which commit is on the server, and rollback is deterministic
(dispatch with the old `sha-`).

**Concurrency (fixed):** top-level is now
`cancel-in-progress: ${{ github.event_name == 'pull_request' }}` — cancels only
redundant PR CI; push-to-develop / tag / dispatch runs are NEVER cancelled
mid-flight, so a deploy can't be killed mid-`compose up`. Rapid develop pushes
both run to completion and their deploy jobs serialize via the job-level
`deploy-prod` group (`cancel-in-progress: false`). (Previously top-level was
`true`, which could cancel a run mid-deploy — the job-level `false` didn't help
because the cancel came from the outer group.)

**Auto-rollback:** a "Record currently-running image" step captures
`docker inspect -f '{{.Config.Image}}' ff_app` into `$GITHUB_ENV` before
`compose up`. A final `if: failure()` step restores that image (local, no pull),
re-verifies `/readiness`, then keeps the job RED. Skipped on the first deploy
(no `ff_app`) or when prev == new. **Limitation:** only the app IMAGE is rolled
back — a forward Liquibase migration that already ran is NOT reverted (old app +
new schema). Acceptable for the test env; a schema rollback needs its own path.

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

**Ops prerequisites (all confirmed set 2026-08-23 except the runner Docker check):**
- `production` environment secrets: `APP_JWT_SECRET`, `POSTGRES_DB`,
  `POSTGRES_USER`, `POSTGRES_PASSWORD` — set via `gh secret set --env production`.
- GitHub Environment named `production` (created; enables optional required-reviewer
  approval + a deployment record).
- Self-hosted runner (`vmi3515719`, online) needs `docker compose` v2 + docker
  daemon access + port 8081 free — the server IS the runner host (compose uses
  `localhost`). **Still unverified from CI; check `docker compose version` on the box.**
- Deploy happens automatically on **merge/push to `develop`**. `git tag vX.Y.Z`
  and manual `workflow_dispatch` (input `version`) are alternate paths.
