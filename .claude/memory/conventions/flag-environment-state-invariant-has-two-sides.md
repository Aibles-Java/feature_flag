---
name: flag-environment-state-invariant-has-two-sides
description: every (flag, environment) pair needs exactly one FlagEnvironmentState row — creating a FLAG backfilled it, creating an ENVIRONMENT did not, so flags predating an environment were invisible to the SDK and untoggleable
metadata:
  type: convention
---

# The flag×environment invariant has two sides; only one was implemented

CLAUDE.md states it: *"a flag always has exactly one state row per environment — never
query flags without joining on this table."* `FeatureFlagServiceImpl.create()` honoured it
by looping every existing environment. `EnvironmentServiceImpl.create()` did **not** do
the mirror loop, so every flag that predated a newly created environment had no row there.

The failure was silent, which is what made it expensive:

- `GET /api/v1/sdk/flags` returned `[]` — the SDK reads flags *through* that table
- `GET /api/v1/sdk/flags/{key}` 404'd
- the admin API could not rescue it: updating a state requires the row to exist
  (`ResourceNotFoundException("Flag state not found for this environment")`)

No error anywhere explained it. Importing a snapshot from another environment
(`EnvironmentTransferServiceImpl`) was the only self-healing path, and nobody would guess
that.

**Rule:** anything that creates a `FeatureFlag` *or* an `Environment` must create the
missing rows for the other side. There are now three such sites — check all of them when
touching either entity:

| Site | Direction |
|---|---|
| `FeatureFlagServiceImpl.create()` | new flag → one row per existing environment |
| `EnvironmentServiceImpl.create()` | new environment → one row per existing flag |
| `EnvironmentTransferServiceImpl.clone()` / `importSnapshot()` | copies / creates rows explicitly |

Two details the fix had to get right:

- **Archived flags get a row too.** They are still flags in the project; skipping them
  reopens the gap the moment one is unarchived. This needed a new plain
  `findAllByProjectId` on `FeatureFlagRepository`, which only had
  `…AndArchivedFalse` / `…AndArchivedTrue`.
- **New rows start disabled.** An environment never inherits another's values — copying
  state is what `clone()` is for.

A service-layer fix only protects environments created from then on, so migration
`019-backfill-missing-flag-environment-states.xml` repairs existing data with
`INSERT … SELECT … LEFT JOIN … WHERE s.id IS NULL` (idempotent, never enables anything,
empty rollback because inserted rows are indistinguishable from legitimate ones). Its SQL
logic is **not** covered by a test — it runs against an empty H2 in CI, which proves only
that the syntax is valid.
