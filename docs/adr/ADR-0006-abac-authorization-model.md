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

`Environment.type` ∈ {DEVELOPMENT, STAGING, PRODUCTION}. `FLAG_STATE_UPDATE` against a `PRODUCTION`
environment is rewritten to require the distinct `FLAG_STATE_UPDATE_PRODUCTION` action, held only by
OWNER by default. Expressing it as an action rather than a hardcoded `role == OWNER` check is what
lets a **custom role** opt into production toggling deliberately — protection and custom roles share
one mechanism instead of fighting each other.

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
- **Gaps remain** (tracked in `docs/ABAC.md` §12): audit reads are still gated by an adapter with no
  `AUDIT_READ` action, and grant/custom-role changes are not written to the audit log.

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
