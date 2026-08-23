# Slack Notifications

This project pushes selected events to Slack. Nothing here needs a server: the
GitHub events run on GitHub's Slack app, the board event runs from
`issue-board.sh`, and the in-app events run inside the Spring Boot service you
already deploy.

All of it is driven by **Slack Incoming Webhooks** (a URL you POST JSON to) or
GitHub's native Slack app. The webhook URL is a secret — never commit it.

## The notification matrix

| # | Event | Mechanism | Channel | Priority |
|---|-------|-----------|---------|----------|
| 1 | CI failed on `develop`/`main` | GitHub Slack app | `#ff-ci` | Critical |
| 2 | CI failed on a PR | GitHub Slack app | `#ff-ci` | High |
| 3 | Coverage ratchet dropped | GitHub Slack app (check run) | `#ff-ci` | High |
| 4 | PR opened / ready for review | GitHub Slack app | `#ff-dev` | High |
| 5 | Card → "Ready For Testing" | `issue-board.sh` webhook | `#ff-dev` | Normal |
| 6 | Release cut (`main` tag) | GitHub Slack app | `#ff-releases` | High |
| 7 | Hotfix merged to `main` | GitHub Slack app | `#ff-releases` | Critical |
| 8 | Flag toggled in `production` | In-app webhook | `#ff-prod-audit` | Critical |
| 9 | Flag toggled in non-prod | In-app webhook | `#ff-audit` | Normal |
| 10 | API key rotated | In-app webhook | `#ff-prod-audit` | High |
| 11 | Flag archived / deleted | In-app webhook | `#ff-audit` | Normal |
| 12 | Migration applied | In-app webhook | `#ff-ci` | Normal |

## Rows 1–4, 6–7 — GitHub's Slack app (no code)

1. Install the **GitHub** app in your Slack workspace (https://slack.github.com).
2. In each target channel, run the slash commands:

```
# In #ff-ci — build failures + coverage check runs
/github subscribe Aibles-Java/feature_flag workflows:{name:"CI"} checks

# In #ff-dev — PR activity
/github subscribe Aibles-Java/feature_flag pulls reviews

# In #ff-releases — releases + pushes to main (hotfixes land here)
/github subscribe Aibles-Java/feature_flag releases
/github subscribe Aibles-Java/feature_flag commits:main
```

Trim the noise so only high-signal events remain:

```
# In #ff-ci — we only care about CI, not issues/PR chatter
/github unsubscribe Aibles-Java/feature_flag issues pulls commits deployments
```

`workflows:{name:"CI"}` scopes to the `CI` workflow in `.github/workflows/workflow.yml`.
GitHub's app reports both pass and fail; if you only want failures, use a channel
that people mute-until-mentioned, or filter on the app's message settings.

## Row 5 — Board "Ready For Testing" (already wired)

`.claude/scripts/issue-board.sh ready <issue#>` posts to Slack **only when
`SLACK_WEBHOOK_URL` is set** in the environment. Unset → silent no-op, so it
never breaks the board move. Export it in your shell profile:

```bash
export SLACK_WEBHOOK_URL="https://hooks.slack.com/services/XXX/YYY/ZZZ"
```

## Rows 8–12 — In-app events

These fire from the Spring Boot service. Configure in `application.properties`
(or via env vars in the deployed environment):

```properties
app.slack.enabled=true
app.slack.webhook-url=${SLACK_WEBHOOK_URL:}
```

- `app.slack.enabled=false` (the default) disables all in-app Slack calls, so
  local dev and tests stay silent.
- The webhook call is **async and best-effort**: a Slack outage or a slow POST
  never blocks or rolls back a flag change / key rotation.

### What each event sends

- **Flag toggled / value changed** (8, 9) — flag key, environment name, project,
  old→new state, and the acting user. Production toggles are the highest-signal
  event this service produces.
- **API key rotated** (10) — environment + acting user. The key itself is
  **never** included.
- **Flag archived / deleted** (11) — flag key + acting user (SDKs stop seeing it).
- **Migration applied** (12) — changeset id, logged on startup after Liquibase runs.

## Security notes

- The webhook URL is a bearer secret: anyone holding it can post to your channel.
  Keep it in an env var / secret store, **not** in a committed properties file.
- In-app payloads never include API keys, JWTs, or password hashes.
- The `security-review-gate` Stop hook will nudge a review if the notifier code
  touches `security/` or `ApiKeyGenerator`.
