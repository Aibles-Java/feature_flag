# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #24** (hash SDK API keys at rest) on branch `feature/issue-24-hash-sdk-api-keys`
(→ `develop`). Implementation complete and fully verified — **not yet committed/pushed**.

Done:
- `util/ApiKeyHasher.java` (NEW) — SHA-256 → lowercase 64-char hex.
- `domain/entity/Environment.java` — `apiKey` → `apiKeyHash` (`api_key_hash`), added
  `lastUsedAt` (`last_used_at`).
- `repository/EnvironmentRepository.java` — `findByApiKey` → `findByApiKeyHash`; added
  `@Transactional @Modifying touchLastUsedAt(id, now, threshold)` (WHERE-guarded).
- `security/ApiKeyAuthenticationFilter.java` — hash header → `findByApiKeyHash`; throttled
  (~5min, in-memory check) `last_used_at` stamp.
- `service/*` + `controller/*` — create/rotate return new `EnvironmentSecretResponse`
  (plaintext once); `apiKey` removed from `EnvironmentResponse` (breaking).
- Migration `009-hash-api-keys.xml` (3 changesets, B is `dbms="postgresql"` pgcrypto
  backfill) + master changelog include.
- Tests: `ApiKeyHasherTest` (NEW), `EnvironmentServiceImplTest` (NEW), updated
  `ApiKeyAuthenticationFilterTest` (+throttle tests) and `SecurityChainIntegrationTest`
  (+valid hashed-key auth). **50/50 pass.**

Verified: migration ran end-to-end on **real Postgres** (backfill hash == app hash, plaintext
column dropped) in a rolled-back tx; security review **clean** (no findings ≥ conf 7).

## Context to Load

- `decisions/0008-hash-sdk-api-keys-at-rest.md` — the design (unsalted SHA-256, one-time
  reveal DTO, throttled last_used_at, migration shape).
- `conventions/liquibase-postgres-only-migrations-on-h2.md` — `dbms="postgresql"` guard rule.
- `conventions/sdk-eval-key-column-h2-500.md` — SDK eval 500s on H2; assert "not 401".

## Next steps

1. Commit all `#24` changes + the memory files (memory gate needs `.claude/memory/` in the
   push).
2. Push `feature/issue-24-hash-sdk-api-keys` (gh is at
   `C:\Users\ACER\AppData\Local\gh-cli\bin\gh.exe` — **not on PATH**; prepend it for
   `gh`/`issue-board.sh`).
3. Open PR with `create-pr` skill (`Closes #24`). Note breaking API change + `last_used_at`
   throttle in the body.
4. `.claude/scripts/issue-board.sh ready 24` after PR opens.

**Parked from previous sessions:**
- Issue #10 branch (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10`
  still pending (board `start 10` also pending).
- Issue #17 branch (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Uncommitted `docs/architecture.md` — land or discard separately (unrelated to #24).
- Issue #14 (SonarQube) waiting on self-hosted infra, holds `decisions/0006-*`.
- Follow-up: make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- Raise `jacoco.line.coverage` above 0.00 (from #3/#4).
