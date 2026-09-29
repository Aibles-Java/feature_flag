---
name: 0035-production-protection-covers-every-production-reaching-action
description: ABAC rule B/D only guarded FLAG_STATE_UPDATE, so archive, key rotation, env delete and snapshot import each changed production with no elevated check — generalised to a table-driven rewrite plus environment resolution
metadata:
  type: decision
---

# Production protection covers every production-reaching action

**Date:** 2026-09-03
**Branch:** `feature/role` (PR #87), commits `5080e4c` + `1c546ec`, on top of the
`origin/develop` merge `42ee946`
**Amends:** [[0034-abac-branch-merge-and-audit-mapping]] / ADR-0006

## The hole

ADR-0006 rule B rewrote `FLAG_STATE_UPDATE` to the OWNER-only
`FLAG_STATE_UPDATE_PRODUCTION` when the target environment was `PRODUCTION`, and rule D
restricted *when* that change could happen. Both fired for **exactly one action**, and only
when the call site passed `ResourceRef.environment(...)`. Four other paths reached production
without ever reading `Environment.type`:

| Path | Gate before | Held by |
|---|---|---|
| `POST /flags/{id}/archive` (+ unarchive) | `FLAG_ARCHIVE` | ADMIN |
| `POST /environments/{id}/api-key/rotate` | `ENV_ROTATE_KEY` | ADMIN |
| `DELETE /environments/{id}` | `ENV_DELETE` | OWNER, no change window |
| `POST /environments/{id}/import` | `requireRoleForEnvironment(OWNER, ADMIN)` — **no `Action` at all** | ADMIN |

**Archive is the one that mattered.** `EvaluationServiceImpl` filters archived flags out of
every SDK response, so archiving *is* an off-switch: an ADMIN denied a production toggle got
the identical outcome by archiving instead. Import was the same hole by another route and was
missed on the first pass — found by the code-reviewer agent, then verified by reading
`EnvironmentTransferServiceImpl:128-231` before acting.

## Decision

1. **Table-driven elevation.** `PermissionService.PRODUCTION_ELEVATED` maps
   `FLAG_STATE_UPDATE → FLAG_STATE_UPDATE_PRODUCTION`, `FLAG_ARCHIVE → FLAG_ARCHIVE_PRODUCTION`,
   `ENV_ROTATE_KEY → ENV_ROTATE_KEY_PRODUCTION`, `ENV_DELETE → ENV_DELETE_PRODUCTION`. Three new
   OWNER-only actions; expressed as actions rather than `role == OWNER` so a custom role can still
   opt in, per ADR-0006 §3. **Rule D now applies to every elevated action**, not just state.
2. **`check()` resolves which production environments an action touches.** Environment-scoped
   call sites name their target; project-scoped ones (archive/unarchive) get every production
   environment under the project via `findAllByProjectId`. Actions outside the table skip the
   lookup entirely — pinned by a `verify(environmentRepository, never())` test so the common path
   never pays a query.
3. **Strictest window wins** across multiple production environments: one closed window denies.
4. **Import is authorised as what it does**, not by role: `FLAG_CREATE` (project-scoped) plus
   `FLAG_STATE_UPDATE` against `ResourceRef.environment(...)`. A **dry run stays project-scoped**
   so an import can still be planned outside the change window.

## Alternatives considered

- **A dedicated `ENV_IMPORT` action.** Rejected: it would need its own `_PRODUCTION` variant and
  a second copy of the rule. Import creates flags and writes state, so naming those two actions
  makes it inherit B and D for free.
- **Rule D only when the call site names one environment** (capability-only for project-scoped
  archive). Rejected in favour of strictest-wins: silently ignoring the change window for the one
  action that can hide a flag from production would repeat the mistake being fixed. **Known sharp
  edge:** disjoint windows (`9–12` and `13–17`) block archiving around the clock. Recoverable —
  `ENV_UPDATE` is not window-guarded, so an OWNER widens a window then archives — but a custom
  role holding `FLAG_ARCHIVE_PRODUCTION` without `ENV_MANAGE_PROTECTION` cannot unblock itself.
- **Covering `ENV_UPDATE`.** Rejected: `type` and the change window are already behind
  `ENV_MANAGE_PROTECTION`, and renaming a production environment changes nothing an SDK evaluates.

## Consequences

- Behavioural: an ADMIN archiving a flag in a project that has a `PRODUCTION` environment,
  rotating a production key, or importing a snapshot into production now gets `403`. Projects
  with no production environment are unaffected.
- Archive costs one extra query on projects that have a production environment. Archive is rare;
  the SDK read path is untouched.
- No migration — the new actions are enum values and `custom_role_action.action` is already
  `VARCHAR(40)`.
- `clone`/`export` deliberately keep the role adapter: export is read-only, and a clone is always
  a new `DEVELOPMENT`-typed environment (`Environment.type` has a `@Builder.Default`; clone does
  not copy the source's), so neither changes what an existing production SDK sees.
- Tests 455 → 470. See [[abac-role-adapters-bypass-attribute-rules]] for the general lesson.
