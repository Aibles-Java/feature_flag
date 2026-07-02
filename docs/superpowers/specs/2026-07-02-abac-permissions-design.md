# ABAC Permission Management — Design

**Date:** 2026-07-02
**Branch:** `feature/role`
**Status:** Approved (design), pending implementation plan
**Scope theme:** ① Governance / permission management (Admin API). Independent of the
SDK and API-key-lifecycle work discussed separately.

---

## 1. Problem

The current authorization model is coarse RBAC:

- `OrganizationMember.role ∈ {OWNER, ADMIN, VIEWER}` is assigned **at the organization
  level only**.
- The org role **cascades to every project, environment, and flag** below it.
- Enforcement lives in the service `*Impl` classes via `PermissionService.requireRole*`.

Two real pain points follow:

- **No project isolation (A).** A VIEWER/ADMIN of an org can see and act on *every*
  project in that org. There is no way to grant a user access to only *some* projects.
- **No production protection (B).** An ADMIN can toggle a flag's state in *any*
  environment, including production. There is no notion of "you may change dev/staging
  but not prod".

## 2. Goals / Non-goals

**In scope (this design):**

- **A — Project-scoped access.** Grant a user a role on a specific project; the effective
  role for that project overrides their org-level role.
- **B — Production protection.** Mutating a flag's state on a `PRODUCTION` environment
  requires a higher bar (OWNER) than on dev/staging.

**Explicitly out of scope (deferred, with seams left in place):**

- **C — Custom user-defined roles.** Keep the three fixed roles. The `role → allowed
  actions` mapping is centralized so C can be added later without reworking call sites.
- **D — Dynamic/context conditions** (time-of-day, IP, resource state). Added later
  through the same attribute-condition seam that B introduces.
- **Environment-scoped grants.** The grant table's `scope_type` is designed to accept
  `ENVIRONMENT` later, but this design only issues `PROJECT` grants.

**Non-goals:** No change to the SDK evaluation chain, the API-key model, or the JWT/API-key
security filters. No data migration of existing `organization_members` rows.

## 3. Design overview

`PermissionService` is already the single choke point for authorization — every service
`*Impl` calls into it and controllers contain no authz logic. In ABAC terms it is already
the **Policy Decision Point (PDP)**. This design evolves it into an explicit PDP while
keeping its existing method signatures working as thin adapters, so the ~30 existing
call sites do not all have to change at once.

Core principle: **most-specific scope wins**, precedence `ENVIRONMENT > PROJECT > ORG`.
This design resolves at `PROJECT` and `ORG`. Production protection (B) is expressed as an
**attribute condition** on the resource, not as per-environment grants.

```
                      PermissionService.check(action, resource)
                                      │
        ┌──────────────────────────────┼──────────────────────────────┐
   Subject attrs                 Resource attrs                  (future) Context
   - userId                      - type (ORG/PROJECT/ENV/FLAG)   - time, IP  (D)
   - effective role @ scope      - env.type (DEV/STAGING/PROD)
     (PROJECT grant else ORG)    - (future) tags/sensitivity
                                      │
                        role → allowed Actions  (static map)
                                      │
                          attribute conditions  (prod rule = B)
                                      │
                                Permit / Deny
```

## 4. Data model changes

Two new Liquibase changesets. **No existing changeset is modified. No data is migrated.**
Both are registered in `db.changelog-master.xml` after `008`.

### 4.1 `009-add-environment-type.xml`

Add an environment attribute that B keys off of.

- New enum `EnvType { DEVELOPMENT, STAGING, PRODUCTION }`.
- Column `environments.type VARCHAR(20) NOT NULL DEFAULT 'DEVELOPMENT'` with a
  `CHECK (type IN ('DEVELOPMENT','STAGING','PRODUCTION'))` constraint (mirrors the
  existing `chk_org_member_role` pattern).
- Existing rows default to `DEVELOPMENT`; admins mark their prod environment explicitly.
- `Environment` entity gains `@Enumerated(EnumType.STRING) private EnvType type;`
- `CreateEnvironmentRequest` / `UpdateEnvironmentRequest` / `EnvironmentResponse` gain a
  `type` field (create defaults to `DEVELOPMENT` when omitted).

### 4.2 `010-create-permission-grants.xml`

```
permission_grant
  id          UUID  PK  (gen_random_uuid())
  user_id     UUID  NOT NULL  FK users(id)          deleteCascade
  scope_type  VARCHAR(20) NOT NULL  CHECK IN ('PROJECT')   -- ENVIRONMENT reserved
  scope_id    UUID  NOT NULL                        -- polymorphic: the project id
  role        VARCHAR(20) NOT NULL  CHECK IN ('OWNER','ADMIN','VIEWER')
  created_at  TIMESTAMPTZ NOT NULL  DEFAULT now()
  UNIQUE (user_id, scope_type, scope_id)
```

`scope_id` is a polymorphic reference (no DB-level FK, because scope may later be a project
or an environment). Referential integrity for the project case is enforced in the service
layer (grant creation validates the project exists). New entity `PermissionGrant` +
`enum ScopeType { PROJECT }` + `PermissionGrantRepository`.

`organization_members` is intentionally left untouched — it remains the source of the
ORG-scope role and of membership (`isMember`).

## 5. Permission resolution

New method on `PermissionService`:

```java
MemberRole effectiveRoleForProject(UUID userId, UUID projectId)
```

Returns the **more permissive** of (a) any `permission_grant(user_id, PROJECT, projectId)`
and (b) the user's `organization_members.role` for the project's org; `null` (deny) when the
user has neither.

> **Implemented as elevate-only (revised from an earlier "grant wins outright" draft).**
> Grants only *raise* a user's role on a project — they never downgrade an org OWNER/ADMIN
> (who therefore keep seeing everything). Taking the max resolves the contradiction between
> "most-specific scope wins" and "OWNER/ADMIN see everything" in favour of the latter, and
> avoids a footgun where a stray narrow grant locks an owner out.

**Project isolation (A)** is achieved by giving a collaborator no cascading org role for
the projects they should not see, and issuing explicit PROJECT grants for the ones they
should. Org OWNER/ADMIN continue to see everything (they administer the org) — the
fallback in step 2 preserves today's behavior for existing members.

**Project isolation (A)** is delivered as *additive elevation*: PROJECT grants raise a
member's role on specific projects. Grants may only target **members of the project's
organization** (see §7), so strict "cannot even see other projects" isolation is **not**
provided — an org VIEWER still sees the org's project list. Hiding non-granted projects from
reads is deferred (see §11.1). All project- and environment-scoped call sites (including
`ProjectServiceImpl.get/update/delete`) resolve access through `effectiveRoleForProject`, so
grant-awareness is consistent across resource types.

## 6. PDP refactor

### 6.1 `Action` enum

```
FLAG_CREATE, FLAG_READ, FLAG_UPDATE, FLAG_DELETE, FLAG_ARCHIVE,
FLAG_STATE_UPDATE,                 -- toggle enabled / value  (the prod-guarded one)
ENV_CREATE, ENV_READ, ENV_UPDATE, ENV_DELETE, ENV_ROTATE_KEY,
PROJECT_CREATE, PROJECT_READ, PROJECT_UPDATE, PROJECT_DELETE,
ORG_UPDATE, ORG_DELETE, MEMBER_INVITE, MEMBER_MANAGE, GRANT_MANAGE
```

### 6.2 `role → Set<Action>` static map

A single static table in code (the seam for future custom roles C). Encodes today's
matrix, e.g. VIEWER → all `*_READ`; ADMIN → read + create/update/toggle; OWNER →
everything including delete + `GRANT_MANAGE`.

### 6.3 Central decision method

```java
void check(Action action, ResourceRef resource)   // throws UnauthorizedException on deny
```

`ResourceRef` carries the resolved scope (org id / project id) and, when relevant, the
target `Environment` (so its `type` attribute is available). `check`:

1. Resolve `effectiveRole` for the resource's project (or org for org-level actions).
2. Deny if `action ∉ roleActions(role)`.
3. Apply attribute conditions (§6.4).

### 6.4 Attribute condition — production protection (B)

One well-tested rule:

> If `action == FLAG_STATE_UPDATE` **and** the resolved target environment has
> `type == PRODUCTION`, then require `effectiveRole == OWNER`. ADMIN is denied on prod
> state changes; dev/staging remain ADMIN-allowed.

This is the single seam through which D (further context conditions) will later be added.

### 6.5 Backward-compatible adapters

Existing `requireRole(...)`, `requireRoleForProject(...)`, `requireRoleForEnvironment(...)`
are kept and re-implemented on top of `effectiveRole`/`check`, so the ~30 current call
sites keep compiling and their tests keep passing. Call sites migrate to `check(Action, …)`
incrementally; the flag-state path migrates first because it is the one that gains the prod
rule.

## 7. Admin API — managing grants

New endpoints (guarded by `check(GRANT_MANAGE, project)` → org OWNER/ADMIN, or a project
OWNER grant):

- `GET    /api/v1/projects/{projectId}/members` — list PROJECT grants for a project.
- `POST   /api/v1/projects/{projectId}/members` — `{ userId, role }` create/update a grant.
- `DELETE /api/v1/projects/{projectId}/members/{userId}` — revoke a grant.

DTOs: `CreateProjectGrantRequest { userId, role }`; responses reuse `MemberResponse`.

**Two mandatory guards (added after security review):**

1. **Target must be an org member.** `upsertGrant` rejects (`404`) a target user who is not a
   member of the project's organization — tenant isolation.
2. **Role ceiling.** A caller may only grant or revoke a role **no more permissive than their
   own effective role** on the project. Without this, a `GRANT_MANAGE`-holding ADMIN could mint
   an OWNER grant for themselves, escalating and defeating the production-protection rule.
   Enforced in both `upsertGrant` and `revokeGrant` (`403` on violation).

## 8. Error handling

Reuse existing exceptions and the `GlobalExceptionHandler` problem-detail mapping:

- Insufficient permission → `UnauthorizedException` → HTTP **403**.
- Missing project/env/grant target → `ResourceNotFoundException` → **404**.
- Duplicate grant handled as upsert (POST updates role if the grant already exists) — no
  `DuplicateResourceException`.

No new exception types.

## 9. Testing

- **`PermissionServiceTest` (extend):** `effectiveRole` precedence (PROJECT grant beats
  ORG role; ORG fallback; no-access deny); `roleActions` matrix per role; `check` allow/deny
  per action; **prod rule** — ADMIN denied on `FLAG_STATE_UPDATE` @ PRODUCTION, allowed @
  DEV/STAGING, OWNER allowed @ PRODUCTION. Keep existing `requireRole*` adapter tests green.
- **Integration (`@SpringBootTest`):**
  - **Scenario A:** user with a PROJECT grant on project X and none on Y can act on X,
    is denied on Y.
  - **Scenario B:** ADMIN toggles flag state on staging (200) but is 403 on production;
    OWNER succeeds on production.
- **Grant admin API:** create/list/revoke happy paths + authz (non-admin cannot manage
  grants).

Coverage feeds the existing JaCoCo ratchet; consider bumping `jacoco.line.coverage` off
`0.00` in the follow-up noted for issue #3.

## 10. Rollout / compatibility

- Additive only: two new changesets, one new column (defaulted), one new table. Existing
  data and behavior for org-level roles are preserved by the resolution fallback.
- `ddl-auto=validate` stays valid because Liquibase owns the new schema.
- **⚠️ Production-protection is NOT retroactive.** Migration `009` backfills every existing
  `environments.type` to `DEVELOPMENT`. On a deployment with existing data, real production
  environments will be typed `DEVELOPMENT` and the prod rule will **not** protect them until an
  admin re-classifies them (`PUT /environments/{id}` with `type=PRODUCTION`). Post-deploy
  operational step: set `type=PRODUCTION` on live prod environments. (Cannot be auto-detected.)
- Sensitive-area gate: the `db/changelog/migrations/` change and any `security/` touch
  trigger a security review before commit (per CLAUDE.md).

## 11. Decisions (resolved during implementation)

1. **List/read isolation precision (A).** **Resolved:** org-role reads keep cascading;
   PROJECT grants are additive elevation only. Hiding non-granted projects from an org
   member's reads is out of scope (would require read-time filtering) — revisit only if a
   concrete need appears.
2. **`GRANT_MANAGE` delegation.** **Resolved: yes** — a project OWNER/ADMIN grant carries
   `GRANT_MANAGE`, subject to the role ceiling in §7.
3. **Grant target scope.** **Resolved:** grants may only target members of the project's
   organization (§7 guard 1).

## 12. Summary of changes

- 2 Liquibase changesets (`009`, `010`) + master registration.
- 1 new table (`permission_grant`), 1 new column (`environments.type`), 2 new enums
  (`EnvType`, `ScopeType`), 1 new entity + repository.
- `PermissionService` evolved into a PDP: `Action` enum, `role → actions` map,
  `effectiveRole`, `check`, prod attribute rule; old methods kept as adapters.
- 3 new grant-management endpoints + DTOs.
- `Environment` entity/DTOs gain `type`.
- Extended unit + integration tests.
