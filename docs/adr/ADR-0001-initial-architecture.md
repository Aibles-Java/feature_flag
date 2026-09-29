# ADR-0001: Initial Architecture

**Status:** Accepted
**Date:** 2026-07-01

## Context

feature_flag is a self-hosted Feature Flag management platform (similar to Flagsmith), providing
multi-tenant flag management for teams. It needs to support:

- A hierarchical tenancy model (organizations → projects → environments)
- Two distinct API consumers with different security requirements: human admins managing flags
  via a dashboard/API, and SDKs evaluating flags at runtime from applications
- A stable, immutable flag identifier that SDKs can rely on across environments

## Decision

**Data model:** `Organization → Project → Environment`, with `FeatureFlag` scoped to a `Project`
and `FlagEnvironmentState` (enabled + value) scoped per `Environment`. A `FlagEnvironmentState`
row is auto-created for every environment whenever a new `FeatureFlag` is created, so a flag
always has exactly one state row per environment.

**Two independent security chains**, defined in `SecurityConfig`:

1. **SDK chain** (`/api/v1/sdk/**`, order=1) — `ApiKeyAuthenticationFilter` reads the
   `X-Environment-Key` header, resolves the `Environment` entity, and sets it as the security
   principal (`ApiKeyAuthenticationToken`). No JWT involved — SDKs never authenticate as a user.
2. **Admin chain** (all other `/api/v1/**`, order=2) — `JwtAuthenticationFilter` validates Bearer
   tokens issued at login, setting `UserPrincipal` (containing the user's UUID) as principal.

**Authorization** is centralized in `PermissionService`, injected into every service
implementation. It reads `UserPrincipal` from `SecurityContextHolder` and checks
`OrganizationMember.role` (OWNER / ADMIN / VIEWER) before any mutating operation — controllers
stay free of authorization logic.

> **Superseded by [ADR-0006](ADR-0006-abac-authorization-model.md).** `PermissionService` is still
> the single choke point and controllers still carry no authorization logic, but the org role no
> longer decides on its own: it is one input to an effective *action set* that also includes
> project-scoped grants and custom roles, with production and change-window rules on top.
> `requireRole*` survives only as an adapter.

**Flag key immutability:** `FeatureFlag.key` is set once at creation and never updated. SDKs
depend on this key being stable across releases; `FeatureFlagServiceImpl.update()` intentionally
ignores changes to it.

**Schema ownership:** PostgreSQL, schema managed entirely by Liquibase
(`spring.jpa.hibernate.ddl-auto=validate`). Changesets are append-only — once a changeset has run,
it is never edited; new changes always add a new changeset file.

## Consequences

- Adding a new environment requires backfilling `FlagEnvironmentState` for every existing flag —
  handled automatically at environment/flag creation time, but any bulk-import or migration
  tooling must replicate this invariant.
- Because the SDK chain never touches JWT/user identity, SDK evaluation has no per-user
  audit trail — only per-environment (via the API key).
- Two independent filter chains mean security changes must be tested against both entry points;
  a fix to one chain does not automatically apply to the other.
- v1 has no identity/segment/percentage-rollout logic — `EvaluationController` returns the same
  flag values to every caller within an environment. This is the basis for the v2 roadmap
  (Segments, Percentage Rollout, Identity & Traits).

## Alternatives considered

- **Single security chain with role-based API-key vs JWT branching inside one filter** — rejected
  because it would couple two very different trust models (machine-to-machine environment keys vs
  human user sessions) into one filter's logic, making both harder to reason about and test in
  isolation.
- **Mutable flag keys with a separate stable ID** — rejected for v1 to keep the SDK contract
  simple; revisit only if a strong renaming use case emerges.
