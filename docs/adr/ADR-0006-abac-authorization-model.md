# ADR-0006: Attribute-Based Authorization — Project Grants, Custom Roles, Production Protection

**Status:** Accepted
**Date:** 2026-08-23
**Supersedes:** the *Authorization* paragraph of [ADR-0001](ADR-0001-initial-architecture.md)

> Numbering note: `ADR-0005` is claimed by the in-flight webhooks branch
> (`feature/issue-36-webhooks`), so this record takes `0006`.

## Context

ADR-0001 established coarse role-based authorization: `OrganizationMember.role` ∈
{OWNER, ADMIN, VIEWER} assigned **at the organization level only**, cascading to every project,
environment and flag beneath it, enforced by `PermissionService.requireRole*` inside the service
layer.

Two limits followed from the single scope:

1. **No project isolation.** An org member could see and act on *every* project in the
   organization. There was no way to give a collaborator elevated access to only some projects.
2. **No production protection.** An ADMIN could toggle a flag's state in *any* environment,
   including production. The model had no notion of "you may change dev and staging, but not prod".

A third need appeared alongside them: teams wanted role definitions of their own rather than the
three fixed ones.

## Decision

Evolve `PermissionService` from a role gate into an explicit **Policy Decision Point** that
resolves an **effective set of actions** and checks the required action against it. Roles become
*names for action sets*, not the unit of comparison.

### 1. `Action` is the vocabulary

Every protected operation is an `Action` (`FLAG_READ`, `FLAG_STATE_UPDATE`, `ENV_ROTATE_KEY`,
`GRANT_MANAGE`, …). `ROLE_ACTIONS` maps each built-in role to its set. Call sites ask
`check(Action, ResourceRef)` rather than naming roles.

### 2. Grants are scoped and additive

`PermissionGrant(user, scope_type=PROJECT, scope_id, role XOR custom_role)` elevates a user on one
project:

```
effectiveActions(user, project) = actionsForRole(org role) ∪ grantActions(grant)
effectiveActions(user, org)     = actionsForRole(org role)
```

Union, not override. A grant can only **add** capability, so a stray narrow grant can never lock out
an org OWNER, and org scope can never inherit project-level authority. `scope_id` is polymorphic so
`ENVIRONMENT` scope can be added later without a schema change.

The cost is that this delivers *elevation*, not *isolation*: an org VIEWER still sees the org's
project list. Hiding non-granted projects from reads would require read-time filtering and is
deliberately deferred.

### 3. Production protection is an attribute rule, not a role

`Environment.type` ∈ {DEVELOPMENT, STAGING, PRODUCTION}. An action against a `PRODUCTION`
environment is rewritten to require a distinct elevated counterpart, held only by OWNER by default.
Expressing it as an action rather than a hardcoded `role == OWNER` check is what lets a **custom
role** opt into production changes deliberately — protection and custom roles share one mechanism
instead of fighting each other.

The rewrite is table-driven (`PRODUCTION_ELEVATED`) and must cover *every* way to change what a
production SDK sees, not just the obvious one — see the amendment below.

A per-environment **change window** (`[start, end)`, may wrap past midnight) further restricts
production state changes; an unset or zero-width window means no restriction, so the rule can never
lock everyone out permanently. Time comes from an injectable `Clock` bean.

### 4. Authority can never be conferred upward

Every definition and delegation point enforces a subset ceiling: granting, revoking, creating or
editing a custom role requires the caller's own effective actions to be a superset of what the
grant or role confers, and `inviteMember` cannot mint a role above the inviter's. Grants may only
target members of the project's organization, and a referenced custom role must belong to the same
organization. Editing an environment's `type` or change window needs the OWNER-only
`ENV_MANAGE_PROTECTION`, so protection cannot be stripped to dodge the rules above.

### 5. The old API stays as adapters

`requireRole*` is kept and reimplemented on top of the new resolution, so the ~30 pre-existing call
sites migrate to `check` incrementally instead of in one commit. The project and environment
adapters are grant-aware; a custom-role grant, having no built-in role to compare against, only
works through `check`.

## Consequences

- **Additive schema.** Migrations `013`–`017` add `environments.type`, `permission_grant`,
  `custom_role` (+ `custom_role_action`), the grant→custom-role link, and the change-window columns.
  `organization_members` is untouched, so existing behaviour is preserved by the org-role fallback.
- **Production protection is not retroactive.** Migration `013` backfills every existing environment
  to `DEVELOPMENT`. After deploying against existing data, real production environments must be
  re-classified (`PUT /environments/{id}` with `type=PRODUCTION`) or rules B/D will not apply to
  them. This cannot be auto-detected.
- **One behavioural change to an existing endpoint.** An org ADMIN toggling flag state on an
  environment typed `PRODUCTION` now gets `403` where it previously succeeded. Everything else
  preserves the pre-ABAC matrix exactly.
- **Authorization now costs more queries.** A project-scoped `check` resolves the project, the org
  membership and any grant. There is no caching yet; a per-request cache is the obvious next step if
  it shows up in the metrics.
- **The audit trail covers permission changes.** `AUDIT_READ` is part of the vocabulary (VIEWER and
  above), so audit access can be conferred by a custom role like any other capability; grants and
  custom-role edits write `PERMISSION_GRANT` / `CUSTOM_ROLE` rows in the same transaction as the
  change. Remaining drift from repo conventions is tracked in `docs/ABAC.md` §12.

## Alternatives Considered

- **Most-specific-scope-wins (grant overrides org role).** Rejected: it contradicts "OWNER/ADMIN
  administer everything" and creates a footgun where a narrow grant silently demotes an owner on
  their own project. Union resolves the contradiction in favour of the org role.
- **Per-environment grants for production protection.** Rejected as the primary mechanism: it would
  require a grant row per environment per user to express "nobody but OWNER touches prod", where an
  attribute on the environment expresses it once. `ENVIRONMENT` scope stays reserved for genuine
  per-environment delegation later.
- **A `permission` table instead of a Java enum.** Rejected: the action vocabulary changes only when
  code changes, so a table would add a join and a drift risk without adding flexibility. Custom
  roles need dynamic *sets*, which `custom_role_action` provides; the vocabulary itself stays static.
- **Hardcoding `role == OWNER` for production.** Rejected: it would make production toggling
  unreachable for custom roles, forcing a second special case the moment a release-manager role is
  wanted.

---

## Amendment (2026-09-03) — production protection covers every production-reaching action

**Problem.** As accepted, the rewrite fired for exactly one action, `FLAG_STATE_UPDATE`, and only
when the call site named the target environment. Three other paths reached production without ever
consulting `Environment.type` or the change window:

| Path | Action needed | Held by | Effect |
|---|---|---|---|
| `POST /flags/{id}/archive` | `FLAG_ARCHIVE` | ADMIN | archived flags are filtered out of every evaluation response — an off-switch by another name |
| `POST /environments/{id}/api-key/rotate` | `ENV_ROTATE_KEY` | ADMIN | invalidates the key every production SDK authenticates with |
| `DELETE /environments/{id}` | `ENV_DELETE` | OWNER | removes the environment; the change window never applied |

The first is the real hole: an ADMIN denied a production toggle could archive the flag instead and
get the same outcome, so rule B was bypassable by an ordinary, documented endpoint.

**Change.**

- Three new actions — `FLAG_ARCHIVE_PRODUCTION`, `ENV_ROTATE_KEY_PRODUCTION`,
  `ENV_DELETE_PRODUCTION` — OWNER-only by default, opt-in for custom roles like the original.
- The rewrite becomes a `Map<Action, Action> PRODUCTION_ELEVATED` rather than one `if`, and the
  change window applies to **every** elevated action instead of flag-state changes alone.
- `check()` resolves which production environments an action touches. Environment-scoped call
  sites name their target; project-scoped ones (archive/unarchive) reach every environment beneath
  the project, so all of its production environments are considered and **the strictest change
  window wins** — one closed window denies the action. Actions outside the table skip the lookup,
  so the common path costs no extra query.
- `EnvironmentServiceImpl.rotateApiKey` and `.delete` now pass `ResourceRef.environment(...)`;
  passing `ResourceRef.project(...)` for a table action silently disables both rules, so two
  regression tests pin it.

**Consequences.**

- One more behavioural change to existing endpoints, in the same spirit as the original: an ADMIN
  archiving a flag in a project that has a `PRODUCTION` environment, or rotating a production
  environment's key, now gets `403` where it previously succeeded. Projects with no production
  environment are unaffected.
- Archiving costs one extra query (the project's environments) on projects that have any
  production environment. Archive is a rare operation; the hot read path is untouched.
- `ENV_UPDATE` is deliberately **not** in the table: `type` and the change window are already
  guarded by `ENV_MANAGE_PROTECTION`, and renaming a production environment does not change what
  its SDKs evaluate.
- No migration — the new actions are enum values, and `custom_role_action.action` is already
  `VARCHAR(40)`.
