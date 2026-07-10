# 0013 — Slack notifications (in-app events + harness + GitHub app)

**Date:** 2026-07-10
**Branch:** `feature/slack-notifications` (commit 0961d7c)

## Decision

Add opt-in Slack notifications across three independent mechanisms, per an agreed
notification matrix documented in `docs/slack-notifications.md`:

1. **GitHub → Slack app** (rows 1–4, 6–7: CI fail, PR opened, releases, hotfix to
   `main`) — no code, configured with `/github subscribe Aibles-Java/feature_flag …`
   in each channel. This is the recommended path for build/PR/release signals.
2. **Board script** (row 5) — `.claude/scripts/issue-board.sh ready` posts to Slack
   via a `slack_notify()` helper. Opt-in: no-op unless `SLACK_WEBHOOK_URL` is set;
   best-effort (`curl -sf … || echo …`) so a failed POST never aborts the board move
   under `set -euo pipefail`. The board move runs *before* the ping (board is SoT).
3. **In-app** (rows 8–11: flag enable/value change, archive/unarchive, API-key
   rotation) — event-driven and async, see architecture below.

## In-app architecture (the important part)

- Service methods publish plain-record domain events via `ApplicationEventPublisher`
  (`notification/event/{FlagStateChangedEvent,FlagArchivedEvent,ApiKeyRotatedEvent}`).
- `SlackEventListener` consumes them with `@Async @TransactionalEventListener(phase =
  AFTER_COMMIT)`. **Why:** after-commit means a rolled-back tx never emits a
  notification; `@Async` (enabled in `config/NotificationConfig`) means Slack latency
  never delays the request thread.
- `SlackNotifier.send()` skips when `app.slack.enabled=false` (the default) or the
  webhook is blank, POSTs `Map.of("text", …)` via a `RestClient` bean, and **swallows
  all exceptions** — a Slack outage can never break business logic.
- Config: `app.slack.enabled` (default false) + `app.slack.webhook-url=${SLACK_WEBHOOK_URL:}`.
  The webhook URL is a **bearer secret** → provided via env var, never committed. To run:
  `export SLACK_WEBHOOK_URL=… APP_SLACK_ENABLED=true` then start the app.

## Security constraints honored (0 critical/high/medium in review)

- `ApiKeyRotatedEvent` carries only env/project/actor email — **never the plaintext key**.
- `SlackNotifier` catch logs `e.getClass().getSimpleName()`, **not** `e.getMessage()`:
  Spring's `ResourceAccessException` message embeds the full webhook URL (secret) on
  connection-level failures. Do not revert this to `getMessage()`.
- `.omc/` added to `.gitignore` (keep `.omc/skills/`) so local OMC state can't be
  committed by accident.

## Deferred

- Row 12 (migration-applied ping) — needs a Liquibase `ChangeExecListener`; low value,
  higher complexity. Left as a follow-up issue, not built.
- Single webhook = single channel today. Prod-critical vs non-prod channel routing
  (a second `production-webhook-url`) is a small follow-up if wanted.

## Note — supersedes a stale fact in [[0012-harness-guards-spotless-coverage]]

That entry (and the 2026-07-10 harness session) recorded "no Java/JDK on this
machine's PATH." **No longer true as of this session:** Java 21 (temurin) + Maven
3.9.16 are installed; `./mvnw spotless:check test` runs locally (170 tests green here).
The 0012 rollout commands can now be run locally.
