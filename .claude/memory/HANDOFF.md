# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Slack notifications** on branch `feature/slack-notifications`, committed (0961d7c) but
**not yet pushed**. See `decisions/0013-slack-notifications.md`. Verified locally:
`./mvnw spotless:check test` → 170 tests, 0 failures; security review 0 critical/high/medium.

Files in the commit: `notification/**` (SlackNotifier, SlackEventListener, SlackProperties,
3 event records), `config/NotificationConfig.java`, event publishes in
`FeatureFlagServiceImpl`/`EnvironmentServiceImpl`, `PermissionService.currentUserEmail()`,
`application.properties` (app.slack.*), `.claude/scripts/issue-board.sh` (row-5 webhook),
`docs/slack-notifications.md`, `.gitignore` (ignore `.omc/`). Plus this memory commit.

**In progress right now:** about to push + open a PR into `develop` to (a) ship the feature
and (b) live-test the GitHub→Slack app (row 4 PR-opened → #ff-dev, row 2 CI → #ff-ci).

## Context to Load

- `decisions/0013-slack-notifications.md` — architecture, security constraints, how to run.
- `docs/slack-notifications.md` — the notification matrix + setup commands.

## Next steps
1. **Push** `feature/slack-notifications` (memory gate now satisfied) and open the PR into
   `develop` via the `create-pr` skill.
2. **User's Slack setup to activate:** create Incoming Webhook → in the deployed/local env set
   `SLACK_WEBHOOK_URL` + `APP_SLACK_ENABLED=true`; run `/github subscribe Aibles-Java/feature_flag …`
   in #ff-ci / #ff-dev / #ff-releases. To run the app locally: `docker compose up -d` FIRST
   (app dies at startup without Postgres — this was the "error, no noti" the user hit), then export
   the two vars, then `./mvnw spring-boot:run`.
3. Follow-up issues: row 12 (Liquibase migration-applied ping) and optional prod-vs-nonprod
   channel routing (second webhook).

**Parked / cross-branch (from prior sessions):**
- Unrelated `docs/ARCHITECTURE.md` change still uncommitted — land or discard separately.
- **#25** actuator — PR #42; **#26** rate limiting — PR #41; **#24** hash SDK keys — MERGED (#40).
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`), #17 (`feature/issue-17-estimate-issue-skill`)
  — commit/push/PR/`ready` pending.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.

**Follow-ups:**
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness` → `liveness`; add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
