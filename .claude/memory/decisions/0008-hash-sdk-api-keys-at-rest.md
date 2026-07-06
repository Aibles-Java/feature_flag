---
name: 0008-hash-sdk-api-keys-at-rest
description: issue #24 — SDK API keys stored as unsalted SHA-256 hex; plaintext one-time reveal via dedicated DTO; last_used_at throttled ~5min
metadata:
  type: decision
---

# Hash SDK API keys at rest (issue #24)

**Decided:** SDK environment API keys are no longer stored in plaintext. Only a
SHA-256 (lowercase hex, 64-char) hash is persisted in `environments.api_key_hash`;
the plaintext is returned to the caller **exactly once** on create/rotate.

## Key choices & rationale

- **Unsalted SHA-256, not bcrypt/argon2.** Keys are 256-bit high-entropy values from
  `SecureRandom` (`ApiKeyGenerator`, 32 bytes → hex). For high-entropy secrets a fast
  unsalted hash is the correct, standard choice — brute force/rainbow tables are
  infeasible, and a slow/salted hash would needlessly break the O(1) indexed lookup by
  hash (`findByApiKeyHash`). Salted/slow hashing is only for low-entropy secrets
  (passwords). Confirmed acceptable by security review.
- **Hashing lives in `util/ApiKeyHasher.hash()`** (sibling of `ApiKeyGenerator`). Both
  the write path (`EnvironmentServiceImpl.create/rotateApiKey`) and read path
  (`ApiKeyAuthenticationFilter`) call it — same function, no bypass gap.
- **One-time reveal via a dedicated DTO.** `apiKey` was **removed** from
  `EnvironmentResponse` (used by get/list/update — they can't expose a secret they no
  longer hold). Create & rotate return a separate `EnvironmentSecretResponse` carrying
  the plaintext once. Chosen over a nullable field on the shared DTO for a clearer
  Swagger contract. This is a **breaking API change**.
- **`last_used_at` audit column, throttled ~5 min.** Updating on every SDK evaluation
  call would add a DB write per request (hot path). The filter checks the in-memory
  `lastUsedAt` and only issues `EnvironmentRepository.touchLastUsedAt(id, now, threshold)`
  when stale; the repo `@Modifying` query has a `WHERE last_used_at < threshold` guard so
  the write stays race-safe under concurrency.

## Migration (see [[liquibase-postgres-only-migrations-on-h2]])

`009-hash-api-keys.xml`, three changesets: (A, all DBs) add `api_key_hash` + `last_used_at`
nullable; (B, `dbms="postgresql"`) `CREATE EXTENSION pgcrypto` + backfill
`UPDATE ... SET api_key_hash = encode(digest(api_key,'sha256'),'hex')`; (C, all DBs) set
NOT NULL + unique, drop old `uq_environments_api_key` + drop `api_key` column. Backfill
parity verified on real Postgres: `encode(digest(x,'sha256'),'hex')` is byte-identical to
Java `ApiKeyHasher`, so existing live keys authenticate unchanged.
