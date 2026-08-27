# 0029 — Environment cloning + schema-versioned flag import/export

**Issue #38** · branch `feature/issue-38-env-clone-import-export` → `develop` · 2026-08-12

Three admin endpoints under `/api/v1/environments/{envId}`: `POST /clone`,
`GET /export`, `POST /import`.

## What was decided (and why)

**A separate `EnvironmentTransferService` + `EnvironmentTransferController`, not more
methods on `EnvironmentService`.** Same URL space (`/api/v1/environments`) — Spring is
fine with two `@RestController`s sharing a base mapping as long as the paths don't
collide — but these three move *flag configuration*, while `EnvironmentService` manages
the environment *record*. Keeping them apart also left `EnvironmentControllerTest`
untouched.

**The export envelope IS the import body.** `GET /export` returns
`{schemaVersion, exportedAt, environmentId, environmentName, projectId, flags[]}`;
`POST /import` takes `{dryRun, conflictStrategy, snapshot}` where `snapshot` is that
same envelope. `ImportEnvironmentRequest.Snapshot`/`FlagEntry` are annotated
`@JsonIgnoreProperties(ignoreUnknown = true)` so the envelope-only fields
(`exportedAt`, `environmentId`, `projectId`) are *dropped, not rejected* — a caller can
pipe an export straight back in. The round-trip test proves this at the wire level: it
serializes the real `EnvironmentSnapshotResponse` with Jackson and deserializes it into
`ImportEnvironmentRequest.Snapshot`, so a field rename on either side fails the test.

**Import ignores the snapshot's own identifiers.** The target project is derived from
the environment in the path, never from `snapshot.projectId` — which is why `Snapshot`
doesn't even *have* that field. A snapshot therefore cannot write outside the
environment the caller was authorized for. This is the main tenancy property of the
feature; don't "helpfully" add `projectId` to the request DTO later.

**Import moves state, never flag metadata.** Missing flags are created (carrying name /
description / valueType / archived from the snapshot, plus state rows for *every*
environment of the project — siblings get the disabled default, mirroring
`FeatureFlagServiceImpl.create`). For a flag that already exists, only its
`FlagEnvironmentState` in *this* environment is touched. Name, description, archived and
valueType are project-wide: rewriting them from an environment-scoped import would
change other environments behind their backs. A **valueType mismatch is always
`SKIPPED`**, under `OVERWRITE` too, for the same reason.

**Outcomes are per flag:** `CREATED` / `UPDATED` / `UNCHANGED` / `SKIPPED` + a `detail`
string, plus a `summary` count block. `conflictStrategy` defaults to **`SKIP`** so an
import that forgets the field can never clobber state. A conflict only exists when a
state row already exists *and differs*; a flag that predates the environment (no state
row — see the gap below) is `CREATED` under either strategy.

**`dryRun` guards the writes directly — it does not rely on transaction rollback.**
Same code path, same computed change set, `if (!dryRun)` around each save. Rollback
would have been fragile (`AuditService.record` runs inside the same transaction) and a
dry run would still have burned ids/sequences.

**Schema version is a constant on the response DTO**
(`EnvironmentSnapshotResponse.SCHEMA_VERSION = 1`), validated in the service. Unknown or
null version → **new `InvalidRequestException` → 400** (there was no 400-mapped
exception in the repo; `GlobalExceptionHandler` gained a handler). Duplicate flag keys
in one snapshot → also 400, up front: two entries for one key would make the result
depend on iteration order. `flags` is `@Size(max = 2000)` — an import walks every entry
with per-flag queries.

**A clone always mints a fresh API key.** Copying the source's hash would widen the
blast radius of one leaked key across two environments. Everything else is copied:
`enabled`, `value`, `rolloutPercent` for every state row, archived flags included.

**Export requires OWNER/ADMIN, not VIEWER**, even though it's a read. The issue scoped
the whole feature to ADMIN+, and a snapshot is an entire environment's configuration in
one payload — a coarser thing to hand out than the per-flag read endpoints.

**Audit:** new `AuditAction.CLONE` (before = the source's `EnvironmentResponse`,
after = the clone's — reads as "cloned from X") and `AuditAction.IMPORT` (after = the
summary, recorded only when a non-dry run actually changed something). `audit_log.action`
is a plain `VARCHAR(32)` with **no check constraint**, so new enum values need no
migration — this change ships zero Liquibase changesets.

## New repository query

`FlagEnvironmentStateRepository.findAllByEnvironmentIdOrderByFlagKey` — `JOIN FETCH` on
the flag, **archived included** (unlike `findAllActiveByEnvironmentId`, which the SDK
evaluation path uses) and `ORDER BY f.key`, so two exports of the same state are
byte-identical and therefore diffable.

## Pre-existing gap noticed, deliberately NOT fixed here

`EnvironmentServiceImpl.create()` does **not** backfill `FlagEnvironmentState` rows for
the project's existing flags — only `FeatureFlagServiceImpl.create()` fans out, in the
other direction. So an environment created after its flags has no state rows at all
(and the SDK returns nothing for it). Import handles this case explicitly (missing state
row → `CREATED`, whatever the strategy), but the underlying asymmetry is still there and
deserves its own issue. Out of scope for #38; flagged in the PR.

## Verification

`./mvnw verify` green — **276 tests** (26 new: 19 service, 7 controller), Spotless clean,
JaCoCo floor met. Every acceptance criterion has a named test, including
`exportThenImportIntoAnotherEnvironment_isLossless` and
`import_dryRun_reportsChangeSetWithoutWriting` (which asserts *no* `save` call at all).

See [[0020-audit-log-flag-org-mutations]] for the audit conventions this follows and
[[0008-hash-sdk-api-keys-at-rest]] for the API-key hashing the clone reuses.
