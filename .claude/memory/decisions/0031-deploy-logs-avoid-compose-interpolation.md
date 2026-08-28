# 0031 — Deploy failure-log steps: `docker logs ff_app`, not `docker compose logs`

Branch `fix/cd-deploy-logs-no-compose-interp` off `origin/develop`.

## Symptom

A `deploy` run surfaced only:

```
error while interpolating services.app.environment.APP_JWT_SECRET: required variable APP_JWT_SECRET is missing a value
error while interpolating services.app.image: required variable APP_IMAGE is missing a value
error while interpolating services.***.environment.POSTGRES_DB ...
Error: Process completed with exit code 1
```

Misleading: it reads like a **missing-secret** problem, but the running `ff_app`
container was healthy on the previously-deployed image — secrets in the
`production` environment were fine. The tell is that **`APP_IMAGE` is also
reported missing**: `APP_IMAGE` is not a secret, it is set from
`steps.img.outputs.image` inside the `env:` of the *Deploy with Docker Compose*
step only.

## Root cause

Three facts compound (see [[0022-cd-deploy-prod-self-hosted-compose.md]]):

1. `docker compose` **re-interpolates the whole compose file** for *any*
   subcommand, including `logs` — it does not skip `${VAR}` just because `logs`
   doesn't need them.
2. `docker-compose.prod.yml` guards every required var with `${VAR:?msg}` —
   empty/unset ⇒ hard error + exit 1 (deliberate: prod must never run on blank
   creds).
3. GitHub Actions **step env is isolated** — the `env:` block on the Deploy step
   does **not** leak into later steps.

The failure-path `docker compose ... logs --tail=100 app` lived in
*Verify deployment health* (and the rollback step's manual-intervention path),
neither carrying the full `env:`. So when a deploy genuinely failed and that
`logs` line ran, compose blew up on the `:?` guards and **masked the real app
logs** — the one thing those steps exist to print — while emitting a
secret-shaped red herring.

## Fix

Replace `docker compose -f docker-compose.prod.yml logs --tail=100 app` with
`docker logs --tail=100 ff_app || true` in both failure-log steps (lines ~391 and
~432 of `workflow.yml`). `docker logs` addresses the container by fixed name
(`ff_app`), reads it directly, and **never parses the compose file**, so it needs
no variables and works in an env-less step. `|| true` keeps the explicit
`exit 1` as the step's outcome even if the container is absent.

Not touched: the Deploy and rollback `compose up/pull/ps` commands — those keep
their `env:` and legitimately need compose.

Verified: `ruby -ryaml` load OK; `docker logs --tail=100 ff_app` confirmed
working on the live self-hosted host (`toanns@…`).

## Pattern

In CI, only use `docker compose <cmd>` in a step that supplies **every** var the
compose file's `${VAR:?}` guards demand. For a pure container-logs / inspect dump
on a failure path, prefer `docker logs <fixed-name>` — no interpolation, no env
coupling, no masking of the real error.
