# Project-scoped access — design

*Written 2026-09-05. Supersedes the org-wide read assumption in ADR-0006.*

## The problem

`MemberRole` answers two unrelated questions with one value:

1. *How much may this person administer the organisation?*
2. *Which projects may this person touch?*

Question 1 genuinely belongs to the organisation. Question 2 belongs to each
project — but today it is derived from the answer to question 1, because the
lowest role already carries org-wide read:

```java
public enum MemberRole { OWNER, ADMIN, VIEWER }

Set<Action> viewer = EnumSet.of(FLAG_READ, ENV_READ, PROJECT_READ, AUDIT_READ);
```

and because listing does not narrow by grant:

```java
public Page<ProjectResponse> listByOrganisation(UUID organisationId, Pageable pageable) {
    check(PROJECT_READ, ResourceRef.org(organisationId));   // asked at ORG scope
    return projectRepository.findAllByOrganizationId(organisationId, pageable);  // returns all
}
```

Measured on a live instance: a VIEWER holding **no grants at all** lists every
project in the organisation and reads their flags.

So "add Nam to project B only" is not a missing feature. It is a sentence the
model cannot express, because there is no axis on which to say it.

## Goal

Make project reach an explicit, per-project decision, and leave the
organisation role to say only what it is actually about.

**Non-goal:** changing what a grant can confer. `PermissionGrant` and
`CustomRole` keep their shape. This is about where the *baseline* comes from,
not about the grant mechanism.

## The model

### Organisation role — administration only

| Role | Actions | Implicit project reach |
|---|---|---|
| `MEMBER` | `ORG_READ`, `MEMBER_READ` | none |
| `ADMIN` | `MEMBER`'s, plus `ORG_UPDATE`, `MEMBER_INVITE`, `MEMBER_MANAGE`, `GRANT_MANAGE`, `ROLE_MANAGE`, `AUDIT_READ`, `PROJECT_CREATE` | none |
| `OWNER` | everything ADMIN has, plus `ORG_DELETE` and every project action | **all projects** |

`MEMBER` is not the empty set, which an earlier draft of this document had it
be. Someone holding nothing at all cannot read the organisation they belong to
(`ORG_READ`) or see who else is in it (`MEMBER_READ`), so the workspace
switcher renders an organisation the user is provably a member of and then
fails to open it. Both actions exist as of the step-2 commit that replaced the
bare `isMember` checks; `MEMBER` keeps them and nothing else.

`VIEWER` was to be retired. **Implementation changed that.** Adding `MEMBER`
beside `VIEWER` rather than in place of it makes the whole model change
additive: nobody's access moves, no migration runs, and the case this design
exists for — scope someone to one project — is answered the moment `MEMBER`
exists.

Retiring `VIEWER` is what makes *scoped access the default* rather than an
option, and that is the part needing the migration in the next section. It is
now a separate decision, deferred until there is real usage to judge it by.
Steps 3 and 4 below are therefore no longer a package.

**OWNER keeps implicit reach on purpose.** Without it an OWNER can create a
project and then be unable to enter it, with nobody able to grant them in —
the organisation locks itself out of its own data. This is the one deliberate
exception to "reach comes only from grants", and it is why `OWNER` must remain
hard to hand out.

### Project reach — from grants only

```
effectiveActionsForProject(user, project)
      = (orgRole == OWNER ? ALL_ACTIONS : {})
      ∪ grantActions(grant on that project)
```

The union survives; the left operand shrinks to nothing for everyone but
OWNER. Grants keep adding only — nothing here lets a grant *remove* what a
role gives.

### Visibility follows reach

An entity is listed only where the caller holds an action on it:

| Endpoint | Today | After |
|---|---|---|
| `GET /projects?organisationId=` | every project in the org | projects where the caller holds `PROJECT_READ` |
| `GET /environments?projectId=` | `check(ENV_READ, project)` | unchanged — already project-scoped |
| `GET /flags?projectId=` | `check(FLAG_READ, project)` | unchanged — already project-scoped |
| `GET /organisations` | orgs the caller is a member of | unchanged |

Only the project list actually changes. The environment and flag lists are
already gated per project and start refusing on their own once `PROJECT_READ`
stops arriving from the org role.

`GET /projects/{id}` already checks at project scope, so it starts refusing
without any edit — which is the point: hiding a row from a list while the
direct fetch still answers would be decoration, not authorization.

## Adding a member: one step, not two

Under this model a member with no grants can do nothing, so the API should
make that state hard to reach rather than easy. `POST /organisations/{orgId}/members`
takes the grants with it:

```json
{
  "email": "nam@company.com",
  "role": "MEMBER",
  "projectGrants": [
    { "projectId": "…B…", "customRoleId": "…Flag operator…" },
    { "projectId": "…Checkout…", "role": "VIEWER" }
  ]
}
```

- `projectGrants` is optional and defaults to empty, so every existing caller
  keeps working.
- One transaction. The call needs `MEMBER_INVITE` at org scope **and**
  `GRANT_MANAGE` on every project named, and the existing
  "cannot confer beyond your own" check runs per grant. Any failure rolls the
  whole thing back — a half-applied invite would leave exactly the useless
  member this endpoint exists to prevent.
- A refusal names the project it failed on. "Forbidden" alone would leave the
  caller guessing which of five rows was the problem.

The separate `POST /projects/{projectId}/members` stays. Setting someone up and
changing their access later are different jobs, and only the first belongs in
an invite form.

## Migration

This is the expensive part and the only part that can break a live
organisation.

Today every member reads every project *because of their role*. Removing that
grants nothing in its place, so without a data migration every non-OWNER loses
everything the moment the new code runs.

`019-project-scoped-access.xml`:

1. For every `organization_members` row with role `ADMIN` or `VIEWER`, insert a
   `permission_grant` (scope `PROJECT`) for **every project in that
   organisation**, carrying the same built-in role. This reproduces today's
   reach exactly, one row at a time.
2. Rewrite the remaining `VIEWER` rows to `MEMBER`. Their reach now lives in
   the grants written by step 1, so the role value no longer has to carry it.
3. `OWNER` and `ADMIN` role values are untouched. `ADMIN` keeps its
   administrative actions and gains explicit grants for the project reach it
   used to get implicitly.

Row count is `members × projects` per organisation. Fine at the sizes this
serves; worth measuring before running against a large tenant.

**The rollback is not symmetric.** Down-migrating restores the roles but cannot
distinguish the grants step 1 wrote from grants a human created afterwards, so
it deliberately does not delete them: after a rollback some people hold
explicit grants they did not have before, which is a superset of their old
access and therefore safe in the direction that matters. Say so in the
changeset comment.

## Frontend

- **Add member dialog** — email, then project access rows, then a single
  checkbox *"Also an organisation admin"* (unchecked = `MEMBER`, checked =
  `ADMIN`). `OWNER` is not offered here; transferring ownership is a separate,
  rarer act that belongs on the members table.
- **Members page** — `VIEWER` disappears from the role selector; the blurbs
  stop describing project access, because the org role no longer decides it.
- **Project list empty state** — "You do not have access to any project yet.
  An organisation admin can grant it." A blank page would read as breakage,
  and under this model an empty list is an ordinary state rather than an error.
- `ROLE_ACTIONS` in `src/api/abac.ts` mirrors the backend table and must move
  with it; `abac.test.ts` pins the counts, so it fails loudly rather than
  greying out actions an admin can really confer.

## Order of work

Each step lands on its own and leaves the system consistent.

| # | Step | Why here |
|---|---|---|
| 1 | Route the 10 adapter call sites through the PDP | An open hole today: 7 webhook operations, `clone`, `export` and the hygiene report reach production environments with no elevation, no change window, and unreachable by custom roles. Independent of this design. |
| 2 | Add `ORG_READ` / `MEMBER_READ`, drop the bare `isMember` checks | Small; finishes the action vocabulary this design assumes |
| 3 | `MEMBER` role + grant-filtered project listing | The model change. Additive: `VIEWER` stays, nothing migrates |
| 4 | Retire `VIEWER`, migration `019` | Only if scoped access should be the default. Deferred |
| 5 | `projectGrants` on the invite endpoint | Makes the empty-member state hard to create |
| 6 | Frontend | Follows the API |

Steps 1 and 2 are worth doing whatever happens to the rest.

## What this costs

Access review stops being free. Where one row per person used to describe
their reach, it now takes one row per person per project. An organisation
whose honest answer to *"should everyone see everyone's work?"* is **yes**
gains nothing here and pays the administrative cost anyway — for them, adding
`MEMBER` alongside today's `VIEWER` default is the better trade, and this
design's step 3 delivers exactly that much on its own.

That is the question to settle before step 4 makes the change irreversible.
