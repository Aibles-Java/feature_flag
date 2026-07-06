---
name: liquibase-postgres-only-migrations-on-h2
description: tests run the full Liquibase changelog on H2 (PostgreSQL mode) — guard Postgres-only SQL (pgcrypto, extensions) with dbms="postgresql" or it breaks the suite
metadata:
  type: convention
---

# Postgres-only migration steps must be guarded for the H2 test run

`application-test.properties` sets `spring.liquibase.enabled=true` and runs the **same**
`db.changelog-master.xml` against H2 (`jdbc:h2:mem:...;MODE=PostgreSQL`). So any migration
using Postgres-specific SQL will execute — and fail — during `./mvnw test` unless guarded.

**Rule:** put Postgres-only statements (extensions like `pgcrypto`, `digest()`,
`gen_random_uuid()` outside a default, etc.) in their own `<changeSet ... dbms="postgresql">`.
On H2 the changeset is skipped entirely.

**Why the skip is safe for data backfills:** migrations run at startup before any test data
exists, so the `environments`/etc. tables are **empty** on H2 at migration time — a
`dbms="postgresql"` backfill `UPDATE` has nothing to migrate and skipping it changes nothing.
The surrounding all-DB changesets (add nullable column → later set NOT NULL + unique) still
work on the empty H2 table.

**Backfill parity (issue #24):** when a Postgres backfill must produce values the app also
computes, verify byte-for-byte equivalence. `encode(digest(x,'sha256'),'hex')` (pgcrypto)
equals Java lowercase-hex SHA-256 (`ApiKeyHasher`) — pin the same known vector
(`SHA-256("abc") = ba7816…15ad`) in a unit test *and* check it via `psql` before trusting
the backfill. See [[0008-hash-sdk-api-keys-at-rest]].

Related gotcha for driving SDK endpoints in tests: [[sdk-eval-key-column-h2-500]].
