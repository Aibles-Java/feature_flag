# Design: Refresh tokens with short-lived access tokens

- **Issue:** #32 — `feat(auth): refresh tokens with short-lived access tokens` (P1 security)
- **Branch:** `feature/issue-32-refresh-tokens`
- **Date:** 2026-07-18
- **Status:** Approved design → implementation plan next

## Problem

A single 24h JWT (`app.jwt.expiration-ms=86400000`) is a long blast radius with no
revocation path: a stolen access token is valid for a full day and cannot be revoked.
We introduce short-lived access tokens (~15 min) plus long-lived, rotating, revocable
refresh tokens with theft detection.

## Goals / acceptance criteria (from #32)

- Refresh rotates the token; reusing a rotated token revokes the whole family.
- Access tokens expire at a configurable short TTL; existing tests updated.
- `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` (revokes the family).
- Liquibase changeset for the `refresh_token` table.
- Security review done (touches auth flow + JWT config).

## Non-goals (YAGNI for v1)

- Scheduled cleanup of expired rows (a `deleteByExpiresAtBefore` repo method is
  provided for future/manual use, but no scheduler is wired).
- "Log out everywhere" (revoke all of a user's families).
- Per-device metadata (user-agent / IP) on the token row.

## Decisions (confirmed with human)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Transport | Refresh token in **JSON body** | Consistent with the existing API (JWT already returned in body); stateless backend/SDK, no browser session/CSRF machinery. |
| Response shape | Rename `token`→`accessToken`, add `refreshToken` + `expiresIn` (access TTL in **seconds**) | Clear OAuth-style contract. Small breaking change — issue accepts "existing tests updated". |
| Logout scope | Revoke **only the current token's family** (one device) | Matches issue scope ("revokes the family"). Other devices stay logged in. |

## Data model — `refresh_token` table (migration 010)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | app-generated (`@GeneratedValue(strategy = UUID)`), same pattern as other entities |
| `user_id` | UUID NOT NULL | **FK → `users(id)` ON DELETE CASCADE** (see below) |
| `family_id` | UUID NOT NULL | groups one rotation lineage = one login/device |
| `token_hash` | VARCHAR(64) NOT NULL UNIQUE | SHA-256 hex of the opaque token; O(1) indexed lookup |
| `expires_at` | TIMESTAMPTZ NOT NULL | `created_at + refresh TTL` (~14d) |
| `rotated_at` | TIMESTAMPTZ NULL | set when this token is consumed by a rotation |
| `revoked_at` | TIMESTAMPTZ NULL | set when the family is revoked (reuse detected or logout) |
| `created_at` | TIMESTAMPTZ NOT NULL | |

Java timestamps are `LocalDateTime` mapped to `TIMESTAMPTZ`, matching the
codebase-wide pattern across the existing entities.

**Indexes:** unique on `token_hash`; index on `family_id` (bulk family revoke);
index on `user_id`.

**FK cascade — deliberately different from the audit log (#31).** The audit ledger
has *no* FK because it must outlive the entities it records. Refresh tokens are the
opposite: they are live session state that must die with the user, so
`ON DELETE CASCADE` is correct here. This contrast is intentional and called out so a
reviewer does not "fix" it to match the audit table.

**Derived state** (no status column; inferred from timestamps + clock):
- **Active** — `rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now`
- **Rotated** — `rotated_at IS NOT NULL` (already consumed)
- **Revoked** — `revoked_at IS NOT NULL`

### Migration number collision (known)

`010` also names the audit-log changeset on the unmerged PR #58. Both branch off
`develop` (latest migration 009). Whichever merges **second** must renumber its
changeset. Resolved at merge time, not now; recorded as a decision comment on #32.

## Token generation & hashing

- **Opaque token:** `SecureRandom` → 32 bytes → 64-char hex (same construction as
  `ApiKeyGenerator`), giving a 256-bit high-entropy secret.
- **At rest:** unsalted **SHA-256** hex — sufficient for a high-entropy random secret
  (same reasoning as `ApiKeyHasher`) and preserves the O(1) indexed lookup by hash. No
  bcrypt/argon2 (those are for low-entropy passwords and would break the hash lookup).
- Generation + hashing live **inside `RefreshTokenService`** (self-contained), so the
  sensitive `ApiKeyGenerator`/`ApiKeyHasher` classes are not touched. The plaintext
  token is returned to the caller exactly once (at issue/rotate) and never stored.

## Flows

### Login — `POST /api/v1/auth/login` (`@Transactional`)

1. Authenticate (unchanged).
2. Issue a short-lived access JWT.
3. Start a **new family**: new `family_id`, new opaque token, insert one `refresh_token`
   row.
4. Return `AuthResponse { accessToken, refreshToken, expiresIn, tokenType, userId, email }`.

> `login()` **must gain `@Transactional`** — it now writes a row. Without it, the write
> path is not atomic with the rest of the request.

### Refresh — `POST /api/v1/auth/refresh { refreshToken }` (`@Transactional`)

1. Hash the presented token; look up the row by `token_hash`.
2. Not found → **401**.
3. `revoked_at != null` (family already dead) → **401**.
4. `expires_at <= now` → **401**.
5. `rotated_at != null` → **REUSE DETECTED**: an already-consumed token was replayed →
   revoke the **entire family** (`UPDATE ... SET revoked_at=now WHERE family_id=? AND
   revoked_at IS NULL`) → **401**. This is the theft-detection path.
6. Active → rotate:
   - **Atomic consume:** `UPDATE refresh_token SET rotated_at=now WHERE id=? AND
     rotated_at IS NULL` and assert `affectedRows == 1`. If `0`, a concurrent request
     already consumed it → treat as reuse (revoke family) → **401**. This closes the
     double-submit / attacker-races-user race that a plain read-then-write leaves open.
   - Load the user; if `!user.enabled` → revoke the family → **401** (do not mint a new
     access token for a disabled account).
   - Insert a new row: same `family_id` and `user_id`, new token, new `expires_at`.
   - Issue a new access JWT.
   - Return `AuthResponse { accessToken, refreshToken(new), expiresIn, ... }`.

### Logout — `POST /api/v1/auth/logout { refreshToken }` (`@Transactional`)

- Hash → find row → revoke that `family_id` (bulk `revoked_at=now`).
- Always return **204**, even for an unknown/already-revoked token (idempotent; avoids
  token/user enumeration). No leak of whether the token existed.

## Config changes — `JwtProperties`

Split the single `app.jwt.expiration-ms` into two:

| Property | Env var | Default (dev) | Meaning |
|----------|---------|---------------|---------|
| `app.jwt.access-expiration-ms` | `APP_JWT_ACCESS_EXPIRATION_MS` | `900000` (15 min) | access JWT TTL |
| `app.jwt.refresh-expiration-ms` | `APP_JWT_REFRESH_EXPIRATION_MS` | `1209600000` (14 d) | refresh token TTL |

- `JwtTokenProvider` uses the **access** TTL.
- `RefreshTokenService` uses the **refresh** TTL for `expires_at`.
- Keep the existing `@Positive` validation on both; extend `JwtProperties` (record +
  Bean Validation) accordingly.
- Update `application.properties`, `application-prod.properties` (no defaults in prod),
  and the README env-var table.

> **Ops migration note:** this **renames** the env var. Prod deployments must set
> `APP_JWT_ACCESS_EXPIRATION_MS` / `APP_JWT_REFRESH_EXPIRATION_MS`; the old
> `APP_JWT_EXPIRATION_MS` is no longer read.

## Components

| Kind | File | Responsibility |
|------|------|----------------|
| Entity | `domain/entity/RefreshToken.java` | maps the table |
| Repository | `repository/RefreshTokenRepository.java` | `findByTokenHash`, atomic consume UPDATE, bulk `revokeFamily(familyId)`, `deleteByExpiresAtBefore` (future use) |
| Service | `service/RefreshTokenService.java` (+ `impl/`) | issue / rotate / revokeFamily / validate; owns generation + hashing + reuse-detection |
| Auth service | `AuthService` / `AuthServiceImpl` | `login()` (now issues refresh), new `refresh(...)`, new `logout(...)` |
| DTOs | `dto/request/RefreshRequest`, `dto/request/LogoutRequest`; `dto/response/AuthResponse` (extended) | request bodies (`@NotBlank refreshToken`) + response contract |
| Controller | `controller/auth/AuthController` | `POST /refresh`, `POST /logout` |
| Config | `config/JwtProperties`, `security/JwtTokenProvider` | two TTLs; access TTL for JWT |

## Security chain

`/api/v1/auth/**` is already public in `SecurityConfig` (login/register are
pre-auth), so `/refresh` and `/logout` are covered without a chain change. **To
verify** during implementation: (a) the auth matcher actually covers the two new paths;
(b) whether `/auth/login` is already behind the existing `security/ratelimit` filter —
if not, note it (do not expand scope). `/refresh` needs no rate limit: guessing a
256-bit token is infeasible.

## Error handling

- Refresh with unknown / expired / revoked / reused token → **401** (`UnauthorizedException`).
- Reuse or concurrent-consume conflict → revoke family, then **401**.
- Disabled user on refresh → revoke family, then **401**.
- Logout → **204** always (idempotent, no enumeration).

## Known limitation (intentional)

Logout revokes the *refresh* family, but an **already-issued access JWT stays valid
until its 15-minute expiry** — access tokens are stateless and not checked against the
DB on each request. This is the standard access/refresh trade-off; the short TTL bounds
the exposure. Documented here so it is a conscious choice, not an oversight.

## Testing

- `RefreshTokenServiceTest` — rotation happy path; reuse detection revokes family;
  concurrent-consume conflict revokes family; expired rejected; revoked rejected;
  disabled-user rejected.
- `RefreshTokenRepositoryTest` (real H2) — `findByTokenHash`, atomic consume returns
  affected-row count, bulk `revokeFamily`.
- `AuthController` integration — `/refresh` happy path + 401 cases; `/logout` → 204.
- Update existing auth tests for the new response shape (`accessToken`) and the short
  access TTL.
- **Security review** before opening the PR (touches auth flow + JWT config +
  `ApiKeyGenerator`-adjacent token handling).
