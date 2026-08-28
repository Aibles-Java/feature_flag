# 0033 — Prod deploy crash: `app.webhook.encryption-key` externalized but not wired

Branch `fix/webhook-key-prod-deploy` off `origin/develop`.

## Symptom

The `develop` CD `deploy` job went red. The compose interpolation for
`APP_CORS_ALLOWED_ORIGINS` succeeded (that secret was set), so the stack came
up — but the new image (`sha-0f3565b`) never became ready. 30×5s of
`curl: (56) Recv failure: Connection reset by peer`, then auto-rollback to the
previous image (`sha-03bac1a`), which *did* become healthy. The tell only
appears in the `docker logs ff_app` dump (see [[0031-deploy-logs-avoid-compose-interpolation]]):

```
APPLICATION FAILED TO START
Binding to target ...WebhookProperties failed:
  Property: app.webhook.encryptionKeyResolved   Value: "false"
  Reason: app.webhook.encryption-key is an unresolved ${...} placeholder —
          the APP_WEBHOOK_ENCRYPTION_KEY environment variable is not set
```

## Root cause

The **CORS commit `0f3565b`** ("externalize CORS") added **two** lines to
`application-prod.properties`, not one:

```
app.webhook.encryption-key=${APP_WEBHOOK_ENCRYPTION_KEY}   ← new, no default
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS}       ← the one that got wired
```

Only CORS got the full value chain (compose `${VAR:?}` + workflow `env:` +
`production` GH secret). The webhook line was added but **never wired** into
`docker-compose.prod.yml` / `workflow.yml` and no `APP_WEBHOOK_ENCRYPTION_KEY`
secret exists ⇒ in prod it stays a literal `${...}`, and `WebhookProperties`'s
`@AssertTrue encryptionKeyResolved` fail-fasts. `WebhookProperties` code exists
since `36cd5c1` (2026-08-06) but was harmless because prod **did not override**
`app.webhook.encryption-key` before `0f3565b` — it fell back to the dev default
in `application.properties`, which is a valid (non-placeholder, ≥64-byte) key, so
validation passed. That is exactly why the rollback image `sha-03bac1a` boots.

Same failure shape as the CORS gap — two secrets externalized in one commit, only
one plumbed end-to-end.

## Decision

Chose the **minimal-boot** fix over wiring the key: **remove the
`app.webhook.encryption-key` override from `application-prod.properties`** so prod
falls back to the built-in dev default (same as before `0f3565b`). App boots;
webhooks are **off by default** (`app.webhook.enabled=false`) so nothing encrypts
a real secret under the dev key yet.

**Tradeoff / TODO:** this reverses the deliberate "required in prod" hardening the
README described. Before **enabling** outbound webhooks in prod, re-externalize the
key: add `APP_WEBHOOK_ENCRYPTION_KEY: ${APP_WEBHOOK_ENCRYPTION_KEY:?...}` to
`docker-compose.prod.yml`, pass it in both the Deploy and Roll back `env:` blocks
of `workflow.yml`, and create the `production` GH secret. The alternative
("wire it up now") was offered and not taken.

## Changes

- `application-prod.properties`: removed the webhook block (8 lines).
- `README.md`: env table `APP_WEBHOOK_ENCRYPTION_KEY` `yes`→`no` (prod); ops note
  reworded — prod no longer pins the key, set a strong value before enabling
  webhooks in prod.
- `docker-compose.yml` (dev): fixed the comment claiming prod requires the key;
  left the local `:?` guard untouched (no dev-startup behaviour change).

## Pattern

When a commit externalizes a secret into `application-prod.properties` with **no
default**, the value chain is only done when it's in **all four** places: prod
props → `docker-compose.prod.yml` `${VAR:?}` → `workflow.yml` `env:` (Deploy **and**
Roll back) → `production` GH secret. A prod prop with no default and no plumbing
is a guaranteed startup crash the moment that image deploys.
