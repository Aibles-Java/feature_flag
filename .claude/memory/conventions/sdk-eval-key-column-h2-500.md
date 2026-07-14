---
name: sdk-eval-key-column-h2-500
description: driving the SDK eval endpoint (/api/v1/sdk/flags) end-to-end on H2 returns 500 — reserved-word `key` column in feature_flags fails to resolve; assert on "not 401" for auth tests
metadata:
  type: convention
---

# SDK evaluation endpoint 500s on H2 (reserved-word `key` column)

Surfaced in issue #24 when the first end-to-end integration test drove
`GET /api/v1/sdk/flags` on H2. The evaluation query joins `feature_flags` and selects the
`key` column; on H2 (`MODE=PostgreSQL`, `globally_quoted_identifiers=true`) this fails with
`Column "ff1_0.key" not found` (SQLState 42S22) → the endpoint returns **500**, not a data
result. It is **pre-existing and H2-only** (Postgres is fine), unrelated to whatever change
you're testing — it just wasn't hit before because no test exercised SDK eval end-to-end.

**Implication for auth/security tests:** to prove an API key authenticates, assert the
request is **not rejected** by the filter (`status != 401`) rather than asserting `200` —
a downstream 500 still means auth succeeded and the request reached the controller. See
`SecurityChainIntegrationTest.sdkEndpointAcceptsValidApiKeyAuthenticatedAgainstStoredHash`.

**Follow-up (not done):** make the `feature_flags.key` mapping/quoting H2-safe (or rename
the column) so SDK eval can be integration-tested for a real 200. Tracked as tech debt.
Related: [[liquibase-postgres-only-migrations-on-h2]], [[springboot4-security-testing]].
