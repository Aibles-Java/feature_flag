# Design: API key lifecycle — multiple keys, expiry, revocation

- **Issue:** _(to be filed)_ — `feat(security): multiple SDK API keys per environment with expiry and revocation`
- **Branch:** `feature/api-key-lifecycle`
- **Date:** 2026-09-05
- **Status:** Reviewed and approved → implementation plan next

## Problem

`environments.api_key_hash` is `NOT NULL UNIQUE`: an environment has exactly one SDK
key, forever, with no expiry and no way to withdraw it other than replacing it.

Three concrete failures follow from that single column:

1. **Rotation is a hard cutover.** `EnvironmentServiceImpl.rotateApiKey()` overwrites the
   hash in place. Every running SDK client is rejected the instant the call returns and
   stays broken until each one is redeployed with the new key. Operators therefore avoid
   rotating, which is the opposite of what key rotation is for.
2. **A leaked key cannot be withdrawn in isolation.** One key serves every consumer, so
   revoking the key the mobile app leaked also kills the batch job and the backend.
3. **Keys never expire.** A contractor's key issued for a two-week integration stays valid
   indefinitely, and nothing in the system records that it was ever meant to be temporary.

This design moves the key to its own entity so an environment can hold several, each with
its own expiry and revocation state.

## Goals / acceptance criteria

- An environment can hold multiple concurrently-valid API keys.
- Each key carries an optional `expires_at`; past it the key authenticates no longer.
- A key can be revoked individually, without affecting the environment's other keys.
- Rotation mints a new key while leaving the old one valid for a grace period, so an SDK
  fleet can be migrated with no downtime.
- Existing keys keep working across the migration, unchanged.
- Creating and revoking keys is authorized through the ABAC PDP and audited.

## Non-goals (deferred, YAGNI for this issue)

- **Per-key scopes.** Explicitly deferred to a later issue. Today's SDK surface is two
  read endpoints, so a scope vocabulary would carry almost no information; it becomes
  worthwhile alongside the v2 identity/traits endpoints, which introduce writes. The
  data model below leaves room for it (a `environment_api_key_scope` join table on the
  `custom_role_action` pattern) but ships nothing.
- Notifying anyone that a key is about to expire (Slack / webhook).
- Scheduled deletion or archival of long-expired key rows.
- Per-key rate limits — see "Rate limiting" below for why this is a deliberate no.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Revocation | **Soft** — set `revoked_at`, never delete the row | Audit rows reference the key id; hard-deleting loses the record of which key did what. `DELETE /api-keys/{id}` is the HTTP verb callers expect, and maps to revocation underneath. |
| Revoking a PRODUCTION key vs the change window | Requires OWNER, but is **exempt from the change window** | Revocation only ever *reduces* access. A key leaked at 03:00 must be withdrawable at 03:00; a window that blocks it protects nobody and the fix (widening the window) is itself OWNER-gated. First deliberate exception to ABAC rule D — recorded in ADR-0006. |
| Old env-level rotate endpoint | Kept; 409 when the environment has more than one active key | Keeps Postman collection, demo flow and any existing caller working while the meaning is unambiguous (exactly one key to rotate). |
| Key display | Store an 8-char `key_prefix` of the plaintext | The plaintext is shown once. Without a prefix every row in a key list is indistinguishable and an operator cannot tell which row corresponds to which deployed config. The prefix is not a secret. |
| Cap per environment | 10 active keys | A bound that prevents unbounded growth without getting in a real operator's way. |

## Data model — migration `019`

Entity `EnvironmentApiKey`, table `environment_api_key` (singular, matching
`refresh_token` / `custom_role` / `webhook_subscription`).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `@GeneratedValue(strategy = UUID)`, as every other entity |
| `environment_id` | UUID NOT NULL | FK → `environments(id)` **ON DELETE CASCADE** — keys are live credentials for that environment and must die with it |
| `name` | VARCHAR(100) NOT NULL | operator-facing label: `ios-app`, `nightly-batch`. **Not unique** — see below |
| `key_hash` | VARCHAR(64) NOT NULL UNIQUE | SHA-256 hex, identical scheme to today's `ApiKeyHasher` |
| `key_prefix` | VARCHAR(8) NOT NULL | first 8 chars of the plaintext, display only |
| `expires_at` | TIMESTAMPTZ NULL | NULL = never expires |
| `revoked_at` | TIMESTAMPTZ NULL | NULL = still valid |
| `last_used_at` | TIMESTAMPTZ NULL | moved down from `environments` |
| `created_by` | UUID NULL | FK → `users(id)` **ON DELETE SET NULL** — the key outlives the person who minted it |
| `created_at` | TIMESTAMPTZ NOT NULL | |

**Constraints and indexes:** unique on `key_hash` (the authentication lookup, O(1));
index on `environment_id` (list endpoint).

**`name` is deliberately not unique.** During a rotation grace period the old and new key
are both active and both want the same label, and every rule for resolving that — suffixes,
timestamps, renaming the old row — is worse than allowing the collision. A name is a human
label, not an identifier; rows are told apart by `key_prefix` and `created_at`, which is
what the prefix exists for.

The `expires_at` / `revoked_at` pair mirrors `refresh_token` exactly, so the two
credential lifecycles read the same way.

**Definition — a key is _active_ when** `revoked_at IS NULL AND (expires_at IS NULL OR
expires_at > now)`. This one predicate decides authentication, the per-environment cap,
whether the legacy rotate endpoint has an unambiguous target, and what the list endpoint
marks as live, so it lives in exactly one place: `EnvironmentApiKey.isActive(Clock)` plus a
matching repository predicate for the counting query.

### Migration steps (the `009` three-step shape)

1. `019-1` — create `environment_api_key`.
2. `019-2` — backfill: `INSERT INTO environment_api_key (environment_id, name, key_hash,
   key_prefix, last_used_at, created_at) SELECT id, 'default', api_key_hash, '',
   last_used_at, now() FROM environments`. The `id` column is left to its
   `defaultValueComputed` default, exactly as `016` does, so the statement stays free of
   any engine-specific UUID function and runs unchanged on PostgreSQL and H2. Nothing is
   re-hashed, so unlike `009` this needs no pgcrypto and no `dbms=` restriction.
3. `019-3` — drop `environments.api_key_hash` and `environments.last_used_at`.

Because the hash is copied rather than recomputed, **every key in the field keeps
authenticating across the migration**. A test asserts exactly this.

`key_prefix` is `NOT NULL` but backfilled empty: existing keys have no recoverable
prefix, and an empty prefix renders as "unknown" rather than inventing one.

### Effect on issue #56

#56 (soft-delete environments with a `last_used_at` deletion guard) reads
`environments.last_used_at`, which step 3 removes. To avoid blocking it, the repository
gains `findLastUsedAtByEnvironmentId` — `MAX(last_used_at)` across the environment's
keys — which is the same quantity #56 needs, computed from the new home.

## Components

| Component | Responsibility |
|---|---|
| `EnvironmentApiKey` (entity) | The credential and its lifecycle state |
| `EnvironmentApiKeyRepository` | Hash lookup, per-environment listing, `touchLastUsedAt`, active-key count |
| `EnvironmentApiKeyService` / `Impl` | Create, list, revoke, rotate; ABAC checks; audit |
| `EnvironmentApiKeyController` | `/api/v1/environments/{envId}/api-keys` |
| `ApiKeyAuthenticationFilter` (changed) | Resolves the key, rejects revoked/expired, sets the principal |
| `EnvironmentServiceImpl` (changed) | `create()` mints the environment's first key; `rotateApiKey()` delegates |
| `EnvironmentTransferServiceImpl` (changed) | `clone()` mints the clone's own first key |

`EnvironmentServiceImpl` is already 200+ lines and owns environment CRUD; key lifecycle
goes in its own service rather than growing that file further.

**Three call sites mint a key today** and all three must move to the new entity:
`EnvironmentServiceImpl.create()` (line 53), `EnvironmentServiceImpl.rotateApiKey()`
(line 147), and `EnvironmentTransferServiceImpl.clone()` (line 66). The third is easy to
miss — a clone deliberately mints its own key rather than copying the source's, and that
invariant must survive the refactor. All three create a key named `default`, and both
`create()` and `clone()` keep returning today's `EnvironmentSecretResponse` carrying that
key's plaintext, so their contracts do not change.

## API surface

| Method | Path | Auth action | Returns |
|---|---|---|---|
| POST | `/api/v1/environments/{envId}/api-keys` | `ENV_KEY_CREATE` | 201 + plaintext, **once** |
| GET | `/api/v1/environments/{envId}/api-keys` | `ENV_READ` | 200, paginated, no plaintext |
| DELETE | `/api/v1/environments/{envId}/api-keys/{keyId}` | `ENV_KEY_REVOKE` | 204 (revokes) |
| POST | `/api/v1/environments/{envId}/api-keys/{keyId}/rotate` | `ENV_ROTATE_KEY` | 200 + plaintext |
| POST | `/api/v1/environments/{envId}/api-key/rotate` | `ENV_ROTATE_KEY` | 200 + plaintext, or **409** |

`CreateApiKeyRequest`: `name` (required, ≤100), `expiresAt` (optional, must be in the
future). `RotateApiKeyRequest`: `graceHours` (optional, default `0`, max `720`).

Two response DTOs, following the existing `EnvironmentResponse` / `EnvironmentSecretResponse`
split: `ApiKeyResponse` (id, name, keyPrefix, expiresAt, revokedAt, lastUsedAt, createdAt,
createdBy) and `ApiKeySecretResponse` (the same plus the one-time `apiKey`). Only POST
endpoints ever return the secret variant.

### Rotation

`POST /api-keys/{keyId}/rotate` mints a new key carrying the old key's `name` unchanged,
then sets `expires_at = now + graceHours` on the old key. `graceHours = 0` (the
default) sets `revoked_at = now` instead, reproducing today's immediate-cutover behaviour
for callers who want it.

The env-level `POST /api-key/rotate` resolves the environment's single active key and
rotates it with `graceHours = 0` — byte-for-byte today's behaviour. With zero or several
active keys it returns 409 naming the key-level endpoint, because "the" key is undefined.

Both rotate endpoints authorize as **`ENV_ROTATE_KEY`**, the action that already means
exactly this and is already in `PRODUCTION_ELEVATED`. Rotation is not decomposed into
`ENV_KEY_CREATE` + `ENV_KEY_REVOKE`: that would open a second door to the same operation
under different action names, and would drag rotation into the revoke window-exemption
below, which it must not have. Rotation is a *planned* change that issues a new production
credential, so it stays fully windowed; only break-glass revocation is exempt.

## Authentication flow — `ApiKeyAuthenticationFilter`

```
X-Environment-Key absent          → 401 "Missing X-Environment-Key header"
hash not found                    → 401 "Invalid API key"
revoked_at IS NOT NULL            → 401 "API key has been revoked"
expires_at < now                  → 401 "API key has expired"
otherwise                         → authenticate
```

The three rejections carry distinct messages and share the `SDK_INVALID_KEY` counter and
the 401 status. Distinguishing them is intentional: "your key expired" and "your key was
revoked" are the difference between a five-minute fix and a support ticket, and the
distinction is only visible to a caller who already holds that key — it tells them nothing
they did not already have. What the endpoint must never reveal is anything about a key the
caller does *not* hold, and an unknown hash yields the flat "Invalid API key" for that
reason.

The lookup is a single indexed query on `key_hash`; validity is evaluated in Java on the
returned row rather than in the `WHERE` clause, so the filter can tell the three cases
apart for the response message.

**The filter and the key service take the `Clock` bean** (`AppConfig.clock()`, already
injected into `PermissionService`) rather than calling `LocalDateTime.now()`. Expiry is the
one part of this feature whose behaviour is a function of time, and the boundary cases —
a key one second before and one second after `expires_at` — are not testable against a
hardcoded clock.

### Principal change

The principal becomes `EnvironmentApiKey` (which exposes `getEnvironment()`) instead of
`Environment`. Three call sites follow:

- `EvaluationController` — `((EnvironmentApiKey) auth.getPrincipal()).getEnvironment()`
- `SdkRateLimitFilter` — `instanceof EnvironmentApiKey key`
- `ApiKeyAuthenticationFilter` — sets it

`EnvironmentApiKey.environment` is mapped `@ManyToOne(fetch = LAZY)` as elsewhere, and the
filter's lookup uses an explicit `JOIN FETCH` so the environment arrives initialized. The
filter runs outside any transaction, so a lazy proxy here would fail at the first
`getEnvironment()` in the controller.

### `last_used_at`

Moves to the key row; the existing mechanism is preserved wholesale — 5-minute in-memory
throttle plus a bulk JPQL `UPDATE` with a `threshold` guard for race safety. Per-key
tracking is what makes "which of these ten keys is still in use?" answerable, which is a
prerequisite for retiring one safely.

### Rate limiting

The SDK bucket stays keyed by **environment id**, not key id. Keying it per key would let
anyone with `ENV_KEY_CREATE` multiply an environment's effective quota by minting keys,
turning the rate limit into a formality.

## Authorization (ABAC)

Four new `Action` values: `ENV_KEY_CREATE` and `ENV_KEY_REVOKE`, with their elevated
counterparts `ENV_KEY_CREATE_PRODUCTION` and `ENV_KEY_REVOKE_PRODUCTION`.

- ADMIN gains `ENV_KEY_CREATE`, `ENV_KEY_REVOKE`.
- OWNER additionally gains `ENV_KEY_CREATE_PRODUCTION`, `ENV_KEY_REVOKE_PRODUCTION`.
- `PRODUCTION_ELEVATED` gains both pairs.

Both actions belong in that table for the reason decision `0035` established: issuing a
new production credential and withdrawing an existing one each change what production
SDKs can do, and an unguarded revoke is an off-switch in exactly the way an unguarded
archive was.

### The change-window exception

`PermissionService` gains a `WINDOW_EXEMPT` set containing `ENV_KEY_REVOKE_PRODUCTION`.
Rule D (the change window) is skipped for actions in it; the elevated-permission check
(rule B) still applies in full.

This is the **first** exception to rule D and it needs to stay conspicuous, because the
rule's value comes from being uniform. The justification is narrow and should not be
extended without the same argument: revocation is monotonically restrictive — it can only
remove access, never grant it — so the window has no attack it prevents, while the delay
it imposes is precisely the window an attacker with a leaked key wants.

`docs/ABAC.md`, `docs/adr/ADR-0006`, and the `CLAUDE.md` permission-model section are
updated to state the exception and the test that pins it.

## Auditing

`AuditAction` gains `CREATE_API_KEY` and `REVOKE_API_KEY`; `ROTATE_API_KEY` stays for
rotation. All three record against `AuditEntityType.API_KEY` with `before`/`after` **null**,
extending today's rule that the audit ledger records that a key event happened and never
what the key was. Key `name` and `id` are safe to record and are; `key_hash` and plaintext
never are.

## Error handling

| Case | Status | Message |
|---|---|---|
| 11th active key | 409 | `Environment has reached the maximum of 10 active API keys` |
| `expiresAt` in the past | 400 | Bean Validation `@Future` |
| Revoking an already-revoked key | 409 | `API key is already revoked` |
| Key belongs to a different environment | 404 | Prevents cross-environment probing via a guessed id |
| Env-level rotate with ≠1 active key | 409 | names the key-level endpoint |

## Testing

TDD; each of these is written before the code that satisfies it.

**Migration** — an existing environment's key still authenticates after `019` runs. This
is the highest-consequence test in the change: getting it wrong logs out every SDK in
production.

**Filter** (`ApiKeyAuthenticationFilterTest`): valid key authenticates and sets an
`EnvironmentApiKey` principal; revoked → 401; expired → 401; `expires_at` exactly now →
expired; unknown hash → 401; all three failures increment the same counter.

**Service** (`EnvironmentApiKeyServiceImplTest`): create returns plaintext once and stores
only the hash; list never exposes plaintext or hash; revoke is idempotent-guarded (409);
the 10-key cap counts only active keys, so revoking frees a slot; rotate with
`graceHours=24` leaves both keys authenticating, and the old one stops at the boundary;
rotate with `graceHours=0` revokes the old key immediately.

**Permissions** (`PermissionServiceTest`): ADMIN creating a key on a PRODUCTION
environment → denied; OWNER inside the window → allowed; OWNER outside the window →
denied; **OWNER revoking outside the window → allowed** (the test that pins the
exception); ADMIN revoking a production key → denied.

**Integration** (`SecurityChainIntegrationTest`, `EvaluationControllerTest`): two live
keys on one environment both reach `/sdk/flags`; revoking one leaves the other working.

Existing tests touching `Environment.apiKeyHash` or the `Environment` principal are
updated: `EnvironmentServiceImplTest`, `EvaluationControllerTest`,
`SecurityChainIntegrationTest`, `RateLimitIntegrationTest`.

## Security review

The change touches `security/` and `db/changelog/migrations/`, so per `CLAUDE.md` a
security review runs before the PR is opened.

## Documentation to update

`CLAUDE.md` (API key generation section, permission model, migration range),
`docs/ABAC.md`, `docs/adr/ADR-0006` (the rule D exception), `docs/architecture.md`,
`docs/main-flows.md` (flow 3 is written around one key per environment), the Postman
collection, and `docs/demo/api-demo-flow.http`.
