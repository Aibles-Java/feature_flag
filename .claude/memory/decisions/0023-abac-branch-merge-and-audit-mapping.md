---
name: 0023-abac-branch-merge-and-audit-mapping
description: merging the 7-week-old feature/role ABAC branch onto develop — adapters restored, develop's service impls as the base, migrations renumbered, and the two model gaps (AUDIT_READ, audit-on-permission-change) closed
metadata:
  type: decision
---

# ABAC branch merge (`feature/role` → up to date with `develop`)

**Date:** 2026-08-23 → 2026-08-25

## What the branch was

`feature/role` (Trang, single commit `ffd356d` "implement ABAC", branched 2026-07-01, never
merged, no PR) turned out to hold a **complete implementation** of project-scoped authorization —
not a plan. It was found by grepping unmerged branches, not from any issue: **there is no GitHub
issue for ABAC at all**, and the design lives in `docs/superpowers/specs/2026-07-02-abac-permissions-design.md`
plus `docs/ABAC.md`. `ADR-0006` was written this session to record the model; see it for the
authorization design itself. This entry records the *merge*, which is where the judgement calls were.

## Decisions

### 1. `requireRole*` adapters came back

The design spec §6.5 said the old methods would be kept as thin adapters so ~30 call sites could
migrate incrementally. **The implementation had deleted them** — every call site was converted in
one commit. That was survivable on the 2026-07-01 base, but `develop` has since added
`AuditService`, which calls `requireRole`. Restoring the adapters (implemented on top of
`effectiveRoleForProject`) was the cheapest correct resolution.

Consequence worth remembering: the project/environment adapters are grant-aware, but a grant
carrying a **custom role** has no `MemberRole` to compare against, so it does *not* satisfy an
adapter. Custom-role capability only flows through `check`. Any call site left on an adapter is
therefore invisible to custom roles — which is exactly how the `AUDIT_READ` gap below arose.

### 2. Conflicts resolved with develop's implementation as the base

13 Java files conflicted. For the four service impls the rule was: **take develop's version whole**
(it carries audit logging, API-key hashing, pagination, metrics, events) and **re-apply the ABAC
conversion on top** (`requireRole*` → `check(Action, ResourceRef)`), rather than the reverse.
Verified afterwards by counting `auditService.record` call sites against develop — 4/5/5/3, exact
match, so nothing from develop was silently dropped.

`EnvironmentResponse` also lost its `apiKey` field here: develop reveals the plaintext exactly once
through `EnvironmentSecretResponse` (issue #24), and the ABAC branch predated that.

### 3. Migrations renumbered `009–013` → `013–017`

See [[sequential-ids-collide-across-long-lived-branches]] for the general rule and why 012 was
skipped. No changeset content changed; none had ever run outside a local dev DB.

### 4. The two places where ABAC and develop genuinely disagreed

Both are the same shape — a feature develop grew *after* the branch was cut, which the action model
never learned about:

- **`AUDIT_READ` did not exist.** `AuditService.list` gated on the legacy adapter, so audit access
  could not be conferred by a custom role and project grants had no effect on it. Fixed: `AUDIT_READ`
  is an `Action` held by VIEWER and above.
- **Permission changes were not audited** — the one class of mutation most worth recording.
  Fixed: `AuditEntityType` gained `PERMISSION_GRANT` / `CUSTOM_ROLE`, `AuditAction` gained
  `GRANT_PERMISSION` / `REVOKE_PERMISSION`, and both services record inside their existing
  transaction. **No migration was needed** — `audit_log.action` and `.entity_type` are plain
  `VARCHAR(32)` with no CHECK constraint (unlike `permission_grant.role`, which does have one).

The same shape is still open in two more places, found by reading the code afterwards:
`OrganizationServiceImpl.get` and `listMembers` gate on `isMember` with no `ORG_READ` /
`MEMBER_READ` action. Whenever a service method is gated by something other than `check`, assume
it is invisible to custom roles until proven otherwise.

## Not decided / still open

Deliberately left for follow-up rather than folded into the merge: no way to change a member's org
role at all (`updateMemberRole` does not exist anywhere), deleting a project or org orphans its
grants (`scope_id` is polymorphic so there is no FK, and unlike `removeMember` neither delete path
cleans up), and the spec's §9 test plan is only partly delivered — no controller tests for the two
new controllers, no repository tests, no `@SpringBootTest` for Scenario A/B. Tracked in
`docs/ABAC.md` §12.
