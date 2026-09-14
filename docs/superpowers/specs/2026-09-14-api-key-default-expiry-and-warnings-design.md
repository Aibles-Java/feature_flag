# Design: API key default expiry and expiry warnings

- **Extends:** [`2026-09-05-api-key-lifecycle-design.md`](2026-09-05-api-key-lifecycle-design.md) —
  lifts its non-goal *"Notifying anyone that a key is about to expire (Slack / webhook)"*
- **Branch:** `feature/api-key-lifecycle`
- **Date:** 2026-09-14
- **Status:** Draft — awaiting review

## Problem

The lifecycle work gave every key an optional `expires_at`, enforced by
`ApiKeyAuthenticationFilter` to the second. Three gaps keep that machinery from being useful
or safe:

1. **Nobody sets an expiry.** `expiresAt` is optional and absent means "never", so almost
   every key is created without one. The feature exists but protects nothing.
2. **Making expiry the default is unsafe today.** Nothing warns anyone before a key dies.
   A production key reaching its deadline returns 401 to every SDK client at once, and those
   clients fall back to the default values compiled into the app — features silently switch
   off for customers.
3. **Rotation cannot rescue an expiring key.** `rotate()` mints the new key with the old key's
   `expiresAt` (pinned by commit `e88d8e6`). An operator who rotates *because* a key expires
   in 7 days gets a new key that also expires in 7 days. The obvious response to a warning
   does not work.

The three are fixed together: a default expiry without warnings is gap 2, and warnings
without a working rotation are gap 3.

## Goals / acceptance criteria

- A key created through `POST /api-keys` without an explicit expiry expires after a
  configured default lifetime (**90 days**).
- A caller can explicitly create a key that never expires.
- Rotating a key gives the new key a fresh lifetime of the same length as the old key's.
- Before an active key expires, a notification goes out at **30, 7 and 1 days** remaining,
  through Slack and through webhooks (new event type `API_KEY_EXPIRING`).
- Each threshold is notified **at most once per key**, even with several app instances.
- A deployment where warnings are on but no channel can deliver them says so loudly.

## Non-goals

- Email notifications.
- Revoking unused keys or deleting long-expired rows automatically.
- Changing the expiry of existing keys. Keys backfilled by `020` keep `expires_at = NULL`.
- Extending an existing key's expiry in place — rotate instead.
- An "expires soon" badge in the UI. `ApiKeyResponse` already carries `expiresAt`; the badge
  is a frontend follow-up.
- Routing Slack messages per team. Slack stays one system-wide webhook; per-environment
  routing is what webhook subscriptions are for.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Default lifetime | **90 days**, `app.api-key.default-ttl=90d` | Long enough to schedule a redeploy, short enough that a forgotten contractor key dies within a quarter. Configurable, not hardcoded. |
| Which mint sites apply the default | **Only `POST /api-keys`.** Environment creation and cloning keep minting a non-expiring `default` key | Those endpoints give the caller no way to state an expiry or opt out, so applying the default there would silently time-bomb the first key of every new environment and change two existing contracts. Operators who want expiring keys create them through the key API. |
| Opting out | `neverExpires: true` in `CreateApiKeyRequest`; combined with `expiresAt` → 400 | "Never" must be a deliberate choice that shows up in the request, not the side effect of leaving a field out. |
| Rotation lifetime | `fresh.expiresAt = now + (old.expiresAt − old.createdAt)`; `NULL` stays `NULL` | Keeps the operator's original policy (a 30-day key rotates into a 30-day key) and makes rotation an actual fix for an expiring key. Replaces the deadline-inheritance rule from `e88d8e6`. |
| Thresholds | 30, 7, 1 days, `app.api-key.expiry-warning.thresholds-days` | Measured against how long an organisation needs to *redeploy*, not how quickly someone reads a message. |
| Schedule | Daily 09:00, `app.api-key.expiry-warning.cron` | Server time zone — the same `Clock` the filter and the change window already use. |
| Channels | Slack + webhook, through the existing `@Async @TransactionalEventListener(AFTER_COMMIT)` pipeline | No new infrastructure. A future channel is one more listener, not a change to the job. |
| Delivery semantics | **At-most-once** per (key, threshold) | The claim commits before the async listeners run, so a Slack outage loses that one message. Better one missed warning than the same warning every day. |
| Keys in a rotation grace period | Warned like any other key | An old key that is still being used close to its grace deadline is exactly the consumer that has not migrated yet; the warning's `lastUsedAt` line says so. |
| Audit | Not audited | The job has no human actor (`audit_log` records one), and sending a notification changes no access. |
| Enforced vs reported expiry | API key expiry is **enforced**; flag expiry stays **reported only** (decision `0028`) | Different consequences: a credential that keeps working past its expiry is a security failure, an expired flag is technical debt. The two intentionally disagree; recorded as a memory decision so nobody "aligns" them later. |

## Data model — migration `023`

`023-add-api-key-expiry-notice.xml`:

| Change | Purpose |
|---|---|
| `environment_api_key.expiry_notice_sent_days INTEGER NULL` | Smallest threshold, in days, already notified for this key. `NULL` = none yet. |
| Index `idx_environment_api_key_expires_at` on `expires_at` | The daily scan filters on `expires_at`. `019` indexes only `environment_id`, and `key_hash` through its UNIQUE constraint. |

**Why `023` and not `021`.** `021` and `022` are used by `feature/invite-member-by-email`
(PR #121), and both have already run against shared local databases. `023` is the first number
free across every open branch.

The column never needs resetting: rotation creates a new row with a fresh `NULL`, and a key's
expiry cannot be extended in place.

## Default expiry on create

`CreateApiKeyRequest` gains `neverExpires` (`boolean`, default `false`). The service resolves
the stored expiry:

```
expiresAt set  and neverExpires   → 400  "expiresAt and neverExpires cannot both be set"
expiresAt set                     → expiresAt            (@Future still applies)
neverExpires                      → NULL
neither                           → now + defaultTtl
```

The mutual-exclusion check is an `@AssertTrue` on the DTO, the same shape
`CreateEnvironmentRequest` uses for its change-window pair.

New `ApiKeyProperties` record bound from `app.api-key.*`, following `HygieneProperties`:
defaults applied in the compact constructor, `@AssertTrue` rejecting a zero or negative
`defaultTtl`.

## Rotation keeps the lifetime length

```
old.expiresAt IS NULL   → fresh.expiresAt = NULL
otherwise               → fresh.expiresAt = now + Duration.between(old.createdAt, old.expiresAt)
```

Both rotate endpoints go through `EnvironmentApiKeyServiceImpl.rotate()`, so the legacy
environment-level endpoint picks this up with no separate change. The existing guards —
revoked → 409, expired → 409, checked before minting — stay in front of it.

For a key whose deadline was itself set by an earlier grace rotation, the computed lifetime
is roughly its age plus the grace period. That is still a sane lifetime, and rotating a key
that is already being retired is an edge case not worth a special rule.

## Expiry warnings

### Components

| Component | Responsibility |
|---|---|
| `ApiKeyProperties` (new) | `defaultTtl`, and nested `expiryWarning`: `enabled`, `cron`, `thresholdsDays` |
| `ApiKeyExpiryConfig` (new) | `@EnableScheduling`; the startup channel check |
| `ApiKeyExpiryScheduler` (new) | `@Scheduled` entry point: loads candidates, picks the due threshold per key, calls the notifier, logs a summary |
| `ApiKeyExpiryNotifier` (new, **separate bean**) | `@Transactional(REQUIRES_NEW)` per key: claims the threshold, publishes the event only if the claim succeeded |
| `EnvironmentApiKeyRepository` (changed) | `findExpiryCandidates(now, horizon)` with `JOIN FETCH` of environment → project; `claimExpiryNotice(id, threshold, now)` |
| `ApiKeyExpiringEvent` (new) | The event record |
| `SlackEventListener` (changed) | `onApiKeyExpiring` |
| `WebhookDispatcher` (changed) | `onApiKeyExpiring`, environment-scoped |
| `WebhookEventType` (changed) | Adds `API_KEY_EXPIRING`. No migration: `event_type` is `VARCHAR(32)` with no CHECK |

### Flow

```
daily 09:00, on every instance
  candidates = keys where revoked_at IS NULL
                      AND now < expires_at <= now + max(thresholds)
  for each key:
    remaining = expires_at − now
    due       = smallest threshold t with remaining <= t days
    if expiry_notice_sent_days IS NOT NULL and expiry_notice_sent_days <= due → skip
    notifier.notify(key, due)                        ← own transaction

notifier.notify(key, due):                           @Transactional(REQUIRES_NEW)
  rows = UPDATE environment_api_key
         SET    expiry_notice_sent_days = :due
         WHERE  id = :id
           AND  revoked_at IS NULL AND expires_at > :now
           AND  (expiry_notice_sent_days IS NULL OR expiry_notice_sent_days > :due)
  if rows == 1 → publish ApiKeyExpiringEvent         ← delivered after this commit
  else         → another instance claimed it, or the key was revoked meanwhile

log INFO "API key expiry scan: {notified} notified, {skipped} skipped"
```

Worked cases with thresholds 30/7/1:

| Remaining | Already sent | Due | Result |
|---|---|---|---|
| 25 days | — | 30 | notify, store 30 |
| 24 days | 30 | 30 | skip |
| 7 days | 30 | 7 | notify, store 7 |
| 5 days (the app was down for two days) | 30 | 7 | notify **once**, store 7 |
| 12 hours | 7 | 1 | notify, store 1 |

### Two traps the code must not fall into

Both get a comment at the call site and a test that fails if the code regresses.

1. **An event published outside a transaction is dropped silently.** Every listener in the
   repo is `@TransactionalEventListener(phase = AFTER_COMMIT)` without `fallbackExecution`,
   so an event published with no active transaction runs no listener, raises no error and
   logs nothing. The scheduler thread has no transaction. The event is therefore published
   *inside* the notifier's transaction — which also means a notification goes out only once
   its claim has committed.
2. **Self-invocation bypasses `@Transactional`.** If the scheduler called a
   `@Transactional` method on itself, the Spring proxy would be skipped, the method would
   run with no transaction, and trap 1 would follow. The notifier is a separate bean for
   exactly this reason.

`REQUIRES_NEW` per key also keeps one failing key from rolling back the claims of the others.

### Misconfiguration must be loud

`SlackNotifier` returns silently when Slack is disabled or has no URL, and `WebhookDispatcher`
returns silently when webhooks are disabled. That fail-safe is right for "a flag was toggled".
Here it is not: the expiry is enforced with certainty and the warning could quietly be zero.

- On `ApplicationReadyEvent`, if warnings are enabled and neither Slack (enabled with a URL)
  nor webhooks are active, log a `WARN`: *"API key expiry warnings are enabled but no
  notification channel is active — keys will expire without notice."* Startup is not blocked.
- Every run logs the `INFO` summary above, so there is evidence the job ran.

Webhooks being enabled while a given environment has no `API_KEY_EXPIRING` subscription is
still silent for that environment. That is the subscription model working as designed, and
it is called out in the docs.

### Event and messages

```java
record ApiKeyExpiringEvent(
    UUID environmentId, String environmentName, String projectName,
    UUID keyId, String keyName, String keyPrefix,
    LocalDateTime expiresAt, LocalDateTime lastUsedAt, long daysLeft) {}
```

Never the hash, never the plaintext. `daysLeft` is the remaining duration rounded **up** to
whole days, so 6 days 23 hours reads as 7.

Slack, following the existing message style and production heuristic:

```
🔴 :key: API key "nightly-batch" (a3f9c1d2…) in *production* (checkout) expires in 7 days
(2026-04-01 00:00). Last used 17 hours ago.
```

`lastUsedAt = NULL` renders as *"Never used"*. That line is what makes the message
actionable: used hours ago means rotate now, used months ago means let it expire, never used
means revoke it.

The webhook payload carries the same fields as data, except `environmentId`, which selects the
subscriptions.

## Configuration

```properties
app.api-key.default-ttl=90d
app.api-key.expiry-warning.enabled=true
app.api-key.expiry-warning.cron=0 0 9 * * *
app.api-key.expiry-warning.thresholds-days=30,7,1
```

Validation at startup: `default-ttl` positive; `thresholds-days` non-empty and all positive.
The test profile sets `app.api-key.expiry-warning.enabled=false` so no scheduler thread runs
during tests; tests call the scheduler method directly.

## Error handling

| Case | Result |
|---|---|
| `expiresAt` and `neverExpires` both set | 400, from the DTO `@AssertTrue` |
| `expiresAt` in the past | 400, unchanged (`@Future`) |
| Slack or webhook delivery fails | Logged by the existing listener or sender; the claim is already committed, so that threshold is not retried |
| Key revoked between the scan and the claim | Claim updates 0 rows; skipped |
| One key's notification throws | Its own transaction rolls back; the loop continues with the next key |

## Testing

TDD; each item is written before the code that satisfies it. A fixed `Clock` throughout.

**Service** (`EnvironmentApiKeyServiceImplTest`): create without an expiry → `now + 90d`;
`neverExpires` → `NULL`; explicit `expiresAt` → stored as given; rotate a 30-day key → the
new key expires 30 days after the rotation; rotate a never-expiring key → `NULL`; the legacy
environment-level rotate follows the same rule. The `e88d8e6` deadline-inheritance test is
replaced by these.

**DTO validation**: `expiresAt` together with `neverExpires` → 400.

**Threshold selection** (pure unit, table-driven): every row of the worked-cases table,
exactly-on-threshold boundaries, and a key beyond the largest threshold.

**Repository** (H2): candidates exclude revoked keys, expired keys, `NULL` expiry and keys
beyond the horizon; `claimExpiryNotice` returns 1, then 0 for the same threshold, then 1 for
a smaller one.

**Notifier and transaction boundary** (`@SpringBootTest`): running the scheduler makes a test
`@TransactionalEventListener(AFTER_COMMIT)` receive exactly one `ApiKeyExpiringEvent`, and
running it again the same day receives none. This is the test that pins both traps.

**Listeners**: `SlackEventListenerTest` covers the message, including "Never used";
`WebhookDispatcherTest` covers `API_KEY_EXPIRING` going only to the key's environment.

**Startup check**: the `WARN` is logged when no channel is active and not logged when one is.

## Security review

Adds a migration under `db/changelog/migrations/`, so it is covered by the security review that
already runs on this branch before the PR (plan Task 7).

## Documentation to update

`CLAUDE.md` (API key section: default lifetime, `neverExpires`, rotation lifetime, the expiry
warning job and its two traps, migration range), `docs/main-flows.md` flow 3,
`docs/architecture.md`, the Postman collection (`neverExpires`, the new webhook event type), and
`.claude/memory`: a decision recording enforced-vs-reported expiry and the rotation lifetime
rule.

## Rollout

Existing keys are untouched: they stay non-expiring. A deployment that leaves both Slack and
webhooks disabled logs the startup `WARN`; production should set `app.slack.enabled=true` with
`SLACK_WEBHOOK_URL`, or enable webhooks and subscribe environments to `API_KEY_EXPIRING`,
before relying on expiring keys.
