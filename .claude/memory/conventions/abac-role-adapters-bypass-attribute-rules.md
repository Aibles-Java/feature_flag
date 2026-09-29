---
name: abac-role-adapters-bypass-attribute-rules
description: a call site still on PermissionService.requireRole* is invisible to the production rules B/D as well as to custom roles — check the adapter list before trusting that an attribute rule is enforced
metadata:
  type: convention
---

# `requireRole*` adapters silently skip the attribute rules

ADR-0006 kept `requireRole(...)` / `requireRoleForProject(...)` / `requireRoleForEnvironment(...)`
as adapters so ~30 call sites could migrate to `check(Action, ResourceRef)` incrementally. The
documented cost was that a **custom-role** grant cannot satisfy an adapter. The undocumented and
more serious cost, found on 2026-09-03:

> **An adapter call site never reaches rules B and D either.** It compares roles and returns. No
> `Action` is resolved, `Environment.type` is never read, and the change window never applies.

That is how `EnvironmentTransferServiceImpl.importSnapshot` (issue #38, merged from develop while
the ABAC branch was open) ended up writing flag state straight into a `PRODUCTION` environment on
ADMIN authority — see [[0035-production-protection-covers-every-production-reaching-action]].

## What to do

- Before claiming an attribute rule is enforced, run
  `grep -rn "requireRole" src/main/java --include=*.java | grep -v PermissionService.java`
  and check every hit. A rule is only enforced on call sites that use `check(...)`.
- Any new code merged from another branch is the likeliest offender: it was written against
  whatever authorization existed on *its* branch, and the merge will not flag the mismatch —
  `develop` has no `check(...)` to conflict with.
- When converting a call site, pass `ResourceRef.environment(projectId, env)` — not
  `.project(projectId)` — whenever the action is in `PRODUCTION_ELEVATED`. A project-scoped ref
  for those actions disables B/D just as quietly as the adapter did. `EnvironmentServiceImplTest`
  pins this for `rotateApiKey`/`delete`; do the same for any new one.

## Still on the adapters (2026-09-03)

`EnvironmentTransferServiceImpl.clone` and `.export`, `FlagHygieneServiceImpl.list`, and eight
sites in `WebhookSubscriptionServiceImpl`. None writes production flag state today, but
subscribing a webhook to a production environment streams every production flag change to an
arbitrary URL on ADMIN authority alone — worth a look before the next release.

Related: [[sequential-ids-collide-across-long-lived-branches]] — the same "long-lived branch meets
a moved develop" shape, one layer down.
