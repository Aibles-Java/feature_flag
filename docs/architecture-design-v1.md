# Architecture & Solution Design Document
## Feature Flag Management Platform

**Version:** 1.0
**Date:** 2026-06-27
**Status:** Current (v1) — v2 Roadmap included

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Goals & Non-Goals](#2-goals--non-goals)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Data Model](#4-data-model)
5. [Component Breakdown](#5-component-breakdown)
6. [Security Architecture](#6-security-architecture)
7. [API Design](#7-api-design)
8. [Deployment Architecture](#8-deployment-architecture)
9. [Key Design Decisions](#9-key-design-decisions)
10. [Error Handling Strategy](#10-error-handling-strategy)
11. [Testing Strategy](#11-testing-strategy)
12. [v2 Roadmap](#12-v2-roadmap)

---

## 1. System Overview

The Feature Flag Management Platform is a self-hosted service similar to Flagsmith, allowing engineering teams to decouple feature deployment from feature release. It provides a centralized system to create, manage, and evaluate feature flags across multiple projects and environments.

### Core Capabilities

| Capability | Description |
|---|---|
| Multi-tenant management | Org → Project → Environment hierarchy |
| Flag lifecycle | Create, update, archive flags with typed values |
| Per-environment state | Each flag has an independent enabled/value per environment |
| SDK evaluation API | Lightweight, API-key-secured endpoint for client SDKs |
| Admin API | Full CRUD via JWT-authenticated REST API |
| Role-based access | OWNER / ADMIN / VIEWER roles per organization |

### Actors

- **SDK Client** — application code that evaluates flags at runtime (no user identity required in v1)
- **Admin User** — developer or product manager managing flags via API or UI
- **Organization Owner** — manages team membership and billing/org-level settings

---

## 2. Goals & Non-Goals

### Goals

- Self-hostable with minimal infrastructure (single JVM process + PostgreSQL)
- Strong multi-tenancy: data isolation at the organization level
- Immutable flag keys to ensure SDK stability across deployments
- Auto-provisioning of flag states when new flags or environments are created
- Full audit trail via `created_at` / `updated_at` timestamps
- OpenAPI documentation included out of the box

### Non-Goals (v1)

- Real-time flag push (no WebSocket / SSE)
- User segmentation or trait-based targeting
- Percentage rollouts / A/B testing
- Per-identity flag overrides
- Analytics or flag evaluation logging
- Multi-region or distributed deployment

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Layer                             │
│                                                                 │
│   ┌──────────────────┐          ┌───────────────────────────┐   │
│   │   Admin Client   │          │      SDK Client           │   │
│   │  (UI / CI / CLI) │          │  (Server / Mobile / Web)  │   │
│   └────────┬─────────┘          └─────────────┬─────────────┘   │
│            │ JWT Bearer                        │ X-Environment-Key│
└────────────┼──────────────────────────────────┼─────────────────┘
             │                                  │
┌────────────▼──────────────────────────────────▼─────────────────┐
│                    Spring Boot Application                       │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   Security Layer                         │   │
│  │   ┌─────────────────────┐  ┌────────────────────────┐   │   │
│  │   │ JwtAuthFilter       │  │  ApiKeyAuthFilter      │   │   │
│  │   │ (Admin chain, ord=2)│  │  (SDK chain, ord=1)    │   │   │
│  │   └─────────────────────┘  └────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │  Auth API       │  │   Admin API      │  │   SDK API     │  │
│  │  /api/v1/auth   │  │   /api/v1/**     │  │ /api/v1/sdk/** │  │
│  └────────┬────────┘  └────────┬─────────┘  └───────┬───────┘  │
│           │                    │                     │          │
│  ┌────────▼────────────────────▼─────────────────────▼───────┐  │
│  │                    Service Layer                           │  │
│  │  AuthService │ OrgService │ ProjectService │ EnvService   │  │
│  │  FlagService │ FlagStateService │ EvaluationService       │  │
│  │                  PermissionService (cross-cutting)         │  │
│  └───────────────────────────────┬────────────────────────────┘  │
│                                  │                               │
│  ┌───────────────────────────────▼────────────────────────────┐  │
│  │                  Repository Layer (Spring Data JPA)        │  │
│  └───────────────────────────────┬────────────────────────────┘  │
│                                  │                               │
└──────────────────────────────────┼───────────────────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │     PostgreSQL 16             │
                    │   (Liquibase-managed schema)  │
                    └──────────────────────────────┘
```

### Request Flow Summary

| Request Type | Auth Mechanism | Principal Type | Handler Chain |
|---|---|---|---|
| `POST /api/v1/auth/**` | None (public) | — | Admin chain (permitted all) |
| `GET/POST /api/v1/**` | Bearer JWT | `UserPrincipal` (userId, email) | Admin chain |
| `GET /api/v1/sdk/**` | `X-Environment-Key` | `ApiKeyAuthenticationToken` (Environment) | SDK chain |

---

## 4. Data Model

### Entity Relationship Diagram

```
┌──────────────┐       ┌────────────────────────┐
│    users     │       │     organizations       │
│──────────────│       │─────────────────────────│
│ id (UUID) PK │       │ id (UUID) PK             │
│ email UNIQUE │       │ name                     │
│ password_hash│       │ slug UNIQUE              │
│ first_name   │       │ created_at               │
│ last_name    │       │ updated_at               │
│ enabled      │       └────────────┬────────────┘
│ created_at   │                    │
│ updated_at   │                    │ 1
└──────┬───────┘                    │
       │                            │ N
       │           ┌────────────────▼───────────────┐
       └──────────►│      organization_members       │
                   │─────────────────────────────────│
                   │ id (UUID) PK                     │
                   │ organization_id FK               │
                   │ user_id FK                       │
                   │ role (OWNER|ADMIN|VIEWER)        │
                   │ created_at                       │
                   │ UNIQUE (organization_id, user_id)│
                   └────────────────────────────────-┘

┌────────────────────────┐
│       projects         │
│────────────────────────│
│ id (UUID) PK           │
│ organization_id FK     │◄── 1 org : N projects
│ name                   │
│ description            │
│ created_at / updated_at│
│ UNIQUE (org_id, name)  │
└────────────┬───────────┘
             │ 1
     ┌───────┴───────────────────────────────┐
     │ N                                     │ N
     ▼                                       ▼
┌──────────────────────────┐   ┌──────────────────────────────┐
│      environments        │   │       feature_flags          │
│──────────────────────────│   │──────────────────────────────│
│ id (UUID) PK             │   │ id (UUID) PK                 │
│ project_id FK            │   │ project_id FK                │
│ name                     │   │ name                         │
│ description              │   │ key UNIQUE per project ←SDK  │
│ api_key UNIQUE (64 hex)  │   │ description                  │
│ created_at / updated_at  │   │ value_type (BOOL/STR/INT/JSON│
│ UNIQUE (project_id, name)│   │ archived (default=false)     │
└────────────┬─────────────┘   │ created_at / updated_at      │
             │                 │ UNIQUE (project_id, key)     │
             │ N               └──────────────┬───────────────┘
             │                                │ N
             └──────────────┬─────────────────┘
                            │
                            ▼
               ┌────────────────────────────────┐
               │     flag_environment_states     │
               │────────────────────────────────│
               │ id (UUID) PK                   │
               │ feature_flag_id FK             │
               │ environment_id FK              │
               │ enabled (boolean)              │
               │ value (TEXT, nullable)         │
               │ created_at / updated_at        │
               │ UNIQUE (flag_id, environment_id│
               └────────────────────────────────┘
```

### Key Data Constraints

| Rule | Enforcement |
|---|---|
| `flag.key` is immutable | Service layer ignores key field in `update()` |
| `FlagEnvironmentState` always exists per (flag, env) pair | Auto-created on flag creation |
| Organization member roles | `CHECK` constraint on `role` column |
| Flag value types | `CHECK` constraint on `value_type` column |
| Unique API key per environment | `UNIQUE` index on `environments.api_key` |
| Cascade deletes | FK constraints with `ON DELETE CASCADE` |

### Value Types

| Type | Use Case | Example Value |
|---|---|---|
| `BOOLEAN` | Simple on/off flags | `"true"` |
| `STRING` | Config strings, theme variants | `"dark"` |
| `INTEGER` | Rate limits, thresholds | `"100"` |
| `JSON` | Complex configuration objects | `"{\"timeout\":30}"` |

---

## 5. Component Breakdown

### 5.1 Controller Layer

Controllers are thin — they receive requests, delegate to services, and return DTOs. No business logic or authorization lives here.

| Controller | Path | Responsibility |
|---|---|---|
| `AuthController` | `/api/v1/auth` | Register, login |
| `OrganizationController` | `/api/v1/organisations` | Org CRUD + member management |
| `ProjectController` | `/api/v1/projects` | Project CRUD |
| `EnvironmentController` | `/api/v1/environments` | Environment CRUD + API key rotation |
| `FeatureFlagController` | `/api/v1/flags` | Flag CRUD + flag state management |
| `EvaluationController` | `/api/v1/sdk/flags` | SDK flag evaluation (read-only) |

### 5.2 Service Layer

Business logic, permission enforcement, and cross-entity coordination.

```
PermissionService (cross-cutting concern)
├── currentUserId()              — extract UUID from SecurityContext
├── requireRole(orgId, roles)    — check membership + role
├── requireRoleForProject()      — traverse Project → Org → check role
└── requireRoleForEnvironment()  — traverse Env → Project → Org → check role

AuthServiceImpl
├── register()   — hash password, persist User
└── login()      — authenticate via AuthManager, issue JWT

OrganizationServiceImpl
├── create()     — persist org + auto-add creator as OWNER
├── update()     — OWNER/ADMIN only
├── delete()     — OWNER only
├── invite()     — OWNER/ADMIN only; ADMIN cannot promote to OWNER
└── removeMember()  — cannot remove last OWNER

FeatureFlagServiceImpl                         ← most complex service
├── create()     — OWNER/ADMIN; auto-create FlagEnvironmentState per env
├── update()     — OWNER/ADMIN; key field intentionally ignored
├── archive()    — OWNER/ADMIN; soft delete (archived=true)
└── updateState()  — OWNER/ADMIN; update enabled+value for specific env

EvaluationServiceImpl                          ← read-only, no auth checks
├── getAllFlags(env)  — JOIN FETCH states + flags, filter archived=false
└── getFlag(env, key)  — lookup by project+key, verify not archived
```

### 5.3 Repository Layer

All repositories extend `JpaRepository<Entity, UUID>`. Notable custom queries:

| Repository | Key Custom Methods |
|---|---|
| `EnvironmentRepository` | `findByApiKey(String)` |
| `FeatureFlagRepository` | `findByProjectIdAndKey(UUID, String)` |
| `FlagEnvironmentStateRepository` | `findAllByEnvironmentIdWithFlag(UUID)` — JOIN FETCH |
| `OrganizationMemberRepository` | `findByOrganizationIdAndUserId(UUID, UUID)` |

### 5.4 Security Components

| Component | Responsibility |
|---|---|
| `JwtTokenProvider` | Sign and validate JWTs using HMAC-SHA256 |
| `JwtAuthenticationFilter` | Per-request JWT validation (Admin chain) |
| `ApiKeyAuthenticationFilter` | Per-request API key lookup (SDK chain) |
| `ApiKeyAuthenticationToken` | Custom Authentication wrapping Environment entity |
| `CustomUserDetailsService` | Load UserDetails by email for Spring Security |
| `ApiKeyGenerator` | `SecureRandom` → 32 bytes → 64-char hex string |
| `PermissionService` | Role-based authorization in service layer |

### 5.5 Cross-Cutting Concerns

| Concern | Implementation |
|---|---|
| Exception handling | `GlobalExceptionHandler` (@RestControllerAdvice), RFC 7807 ProblemDetail |
| DTO mapping | MapStruct mappers (compile-time generated) |
| Schema migration | Liquibase changelogs (001–007), never modify applied changesets |
| API documentation | SpringDoc OpenAPI, auto-generated from annotations |

---

## 6. Security Architecture

### 6.1 Dual Security Filter Chain

Spring Security allows multiple `SecurityFilterChain` beans ordered by priority. This project uses order to route SDK vs Admin traffic to completely separate authentication mechanisms.

```
Incoming Request
       │
       ▼
┌─────────────────────────────────────────────────┐
│ Chain 1 — SDK (order=1, matches /api/v1/sdk/**) │
│                                                 │
│  ApiKeyAuthenticationFilter                     │
│    reads X-Environment-Key header               │
│    → EnvironmentRepository.findByApiKey()       │
│    → sets ApiKeyAuthenticationToken principal   │
│    → Environment object available in controller │
└────────────────────────────────┬────────────────┘
                                 │ (no match → falls through)
                                 ▼
┌─────────────────────────────────────────────────┐
│ Chain 2 — Admin (order=2, matches /api/v1/**)   │
│                                                 │
│  Public paths: /api/v1/auth/**, /swagger-ui/**  │
│                /api-docs/**                     │
│                                                 │
│  JwtAuthenticationFilter                        │
│    reads Authorization: Bearer <token>          │
│    → JwtTokenProvider.validateToken()           │
│    → extract email claim                        │
│    → CustomUserDetailsService.loadUserByUsername│
│    → sets UsernamePasswordAuthenticationToken   │
└─────────────────────────────────────────────────┘
```

### 6.2 JWT Specification

| Field | Value |
|---|---|
| Algorithm | HMAC-SHA256 |
| Secret | Min 512-bit (64 chars) configured via `app.jwt.secret` |
| Expiration | 24 hours (`app.jwt.expiration-ms=86400000`) |
| Subject claim | User UUID |
| Extra claim | `email` |
| Storage | Client-side only (stateless) |

### 6.3 Permission Matrix

| Operation | OWNER | ADMIN | VIEWER |
|---|---|---|---|
| Read flags / projects / envs | ✓ | ✓ | ✓ |
| Create / update flags | ✓ | ✓ | ✗ |
| Enable / disable flag state | ✓ | ✓ | ✗ |
| Create / update environments | ✓ | ✓ | ✗ |
| Rotate API key | ✓ | ✓ | ✗ |
| Invite VIEWER members | ✓ | ✓ | ✗ |
| Invite ADMIN/OWNER members | ✓ | ✗ | ✗ |
| Delete org / project / env | ✓ | ✗ | ✗ |
| Archive flag | ✓ | ✓ | ✗ |
| Remove members | ✓ | ✗ | ✗ |

**Rules:**
- ADMIN cannot promote another user to OWNER
- The last OWNER of an organization cannot be removed
- Permission checks are performed in the **Service layer**, not controllers

### 6.4 API Key Security

- Generated using `SecureRandom` (cryptographically secure)
- 32 bytes → 64-char lowercase hex string (~192 bits of entropy)
- Stored in plaintext in DB (lookup by value is required for SDK auth)
- Rotatable at any time via `POST /api/v1/environments/{id}/api-key/rotate`
- Unique constraint prevents accidental collision

---

## 7. API Design

### 7.1 Design Principles

- **RESTful resources** — nouns in URLs, HTTP verbs for actions
- **Consistent response format** — `ProblemDetail` (RFC 7807) for errors
- **Pagination** — not yet implemented (v2 concern)
- **Versioning** — URI versioning (`/api/v1/`)
- **Soft delete** — flags are archived, not hard-deleted (SDK stability)

### 7.2 Endpoint Inventory

#### Auth (public)
```
POST   /api/v1/auth/register    → 201 AuthResponse
POST   /api/v1/auth/login       → 200 AuthResponse
```

#### Organizations (JWT)
```
POST   /api/v1/organisations                          → 201 OrganizationResponse
GET    /api/v1/organisations                          → 200 OrganizationResponse[]
GET    /api/v1/organisations/{orgId}                  → 200 OrganizationResponse
PUT    /api/v1/organisations/{orgId}                  → 200 OrganizationResponse
DELETE /api/v1/organisations/{orgId}                  → 204
GET    /api/v1/organisations/{orgId}/members          → 200 MemberResponse[]
POST   /api/v1/organisations/{orgId}/members          → 201 MemberResponse
DELETE /api/v1/organisations/{orgId}/members/{userId} → 204
```

#### Projects (JWT)
```
POST   /api/v1/projects                  → 201 ProjectResponse
GET    /api/v1/projects?organisationId=  → 200 ProjectResponse[]
GET    /api/v1/projects/{projectId}      → 200 ProjectResponse
PUT    /api/v1/projects/{projectId}      → 200 ProjectResponse
DELETE /api/v1/projects/{projectId}      → 204
```

#### Environments (JWT)
```
POST   /api/v1/environments                       → 201 EnvironmentResponse
GET    /api/v1/environments?projectId=            → 200 EnvironmentResponse[]
GET    /api/v1/environments/{envId}               → 200 EnvironmentResponse
PUT    /api/v1/environments/{envId}               → 200 EnvironmentResponse
DELETE /api/v1/environments/{envId}               → 204
POST   /api/v1/environments/{envId}/api-key/rotate → 200 EnvironmentResponse
```

#### Feature Flags (JWT)
```
POST   /api/v1/flags                                          → 201 FeatureFlagResponse
GET    /api/v1/flags?projectId=                               → 200 FeatureFlagResponse[]
GET    /api/v1/flags/{flagId}                                 → 200 FeatureFlagResponse
PUT    /api/v1/flags/{flagId}                                 → 200 FeatureFlagResponse
DELETE /api/v1/flags/{flagId}                                 → 204 (archives)
GET    /api/v1/flags/{flagId}/environments/{envId}            → 200 FlagStateResponse
PUT    /api/v1/flags/{flagId}/environments/{envId}            → 200 FlagStateResponse
```

#### SDK Evaluation (API Key)
```
GET    /api/v1/sdk/flags              → 200 FlagEvaluationResponse[]
GET    /api/v1/sdk/flags/{flagKey}    → 200 FlagEvaluationResponse
```

### 7.3 SDK Evaluation Response

The SDK API is intentionally minimal — it only exposes what client applications need:

```json
[
  {
    "flagKey": "new-checkout-flow",
    "enabled": true,
    "value": "v2",
    "valueType": "STRING"
  },
  {
    "flagKey": "dark-mode",
    "enabled": false,
    "value": null,
    "valueType": "BOOLEAN"
  }
]
```

### 7.4 Error Response Format (RFC 7807)

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "FeatureFlag not found with id: abc-123",
  "instance": "/api/v1/flags/abc-123"
}
```

For validation errors, field-level details are included:
```json
{
  "status": 400,
  "title": "Bad Request",
  "errors": {
    "key": "must match \"[a-z0-9_-]+\"",
    "name": "must not be blank"
  }
}
```

---

## 8. Deployment Architecture

### 8.1 Development / Single-Node (Docker Compose)

```
┌─────────────────────────────────────┐
│           Docker Compose            │
│                                     │
│  ┌───────────────────────────────┐  │
│  │    app container              │  │
│  │    eclipse-temurin:21-jre     │  │
│  │    Port: 8080                 │  │
│  │    Restart: unless-stopped    │  │
│  └───────────────┬───────────────┘  │
│                  │ depends_on       │
│                  │ (healthy)        │
│  ┌───────────────▼───────────────┐  │
│  │    postgres container         │  │
│  │    postgres:16-alpine         │  │
│  │    Port: 5432                 │  │
│  │    Volume: postgres_data      │  │
│  │    Healthcheck: pg_isready    │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

### 8.2 Dockerfile (Multi-Stage Build)

```
Stage 1 — Builder (eclipse-temurin:21-jdk-alpine)
  ├── Copy pom.xml → download dependencies (layer cache)
  ├── Copy source
  └── mvn clean package -DskipTests → target/*.jar

Stage 2 — Runtime (eclipse-temurin:21-jre-alpine)
  ├── Copy JAR from builder stage
  ├── EXPOSE 8080
  └── ENTRYPOINT ["java", "-jar", "app.jar"]
```

Layer caching on dependencies keeps rebuild times fast when only source code changes.

### 8.3 Environment Variables (Production)

| Variable | Purpose | Example |
|---|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://db:5432/feature_flag_db` |
| `SPRING_DATASOURCE_USERNAME` | DB user | `ff_user` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | (secret) |
| `APP_JWT_SECRET` | HMAC signing key (512+ bits) | (secret, min 64 chars) |
| `APP_JWT_EXPIRATION_MS` | Token TTL | `86400000` |

### 8.4 Database Migration Strategy

- **Tool:** Liquibase with XML changelogs
- **Location:** `src/main/resources/db/changelog/migrations/001–007`
- **Runtime mode:** `spring.jpa.hibernate.ddl-auto=validate` — Hibernate only validates, never modifies schema
- **Rule:** Never modify an applied changeset. Always add a new numbered changeset.
- **Rollback:** Each changeset includes explicit rollback instructions (`dropTable`, etc.)

---

## 9. Key Design Decisions

### 9.1 Flag Key Immutability

**Decision:** `FeatureFlag.key` is set at creation and cannot be changed.

**Rationale:** The key is the stable identifier used by SDK clients. If a key could change, existing SDK code would silently evaluate the wrong flag. The `update()` service method intentionally ignores the key field.

**Trade-off:** Flags with bad keys must be archived and recreated.

---

### 9.2 Auto-Provisioning of FlagEnvironmentState

**Decision:** When a flag is created, `FlagEnvironmentState` rows are auto-created for all existing environments in the project (defaulting to `enabled=false`).

**Rationale:** Guarantees that every `(flag, environment)` pair always has exactly one state row. This simplifies queries (no LEFT JOIN checking for null state) and prevents runtime errors when SDKs request flag state.

**Trade-off:** Creating a flag in a project with many environments is slightly more expensive.

---

### 9.3 Permission Checks in Service Layer

**Decision:** All authorization logic lives in `PermissionService`, injected into service implementations. Controllers are unaware of permissions.

**Rationale:** Keeps controllers thin and reusable. Authorization logic is colocated with business logic, making it harder to accidentally expose an endpoint without protection. Future service-to-service calls would also benefit from this.

---

### 9.4 Dual Security Filter Chain

**Decision:** Two separate `SecurityFilterChain` beans with `@Order` annotations instead of conditional logic in a single chain.

**Rationale:** SDK and Admin traffic have fundamentally different auth mechanisms, principals, and permission models. Separating them avoids conditional branching and makes each chain independently comprehensible.

---

### 9.5 Soft Delete for Flags

**Decision:** `DELETE /api/v1/flags/{id}` sets `archived=true` rather than removing the row.

**Rationale:** Preserves historical state and audit trail. SDK evaluation filters out archived flags, so they become inactive without data loss. Hard deletion would also cascade-delete all `FlagEnvironmentState` history.

---

### 9.6 Liquibase Over Hibernate DDL

**Decision:** Schema is owned entirely by Liquibase. Hibernate is set to `validate` only.

**Rationale:** Liquibase provides reproducible, versioned, rollback-capable schema migrations. Hibernate's DDL auto-generation is suitable for rapid prototyping but unreliable for production schema management.

---

## 10. Error Handling Strategy

```
Controller
  │
  ├── validates request body (@Valid annotations)
  │     └── MethodArgumentNotValidException → 400 with field errors
  │
  └── calls Service
        │
        ├── ResourceNotFoundException    → 404 ProblemDetail
        ├── DuplicateResourceException   → 409 ProblemDetail
        ├── UnauthorizedException        → 403 ProblemDetail
        └── Exception (unhandled)        → 500 ProblemDetail

GlobalExceptionHandler (@RestControllerAdvice)
  └── maps all exceptions to RFC 7807 ProblemDetail responses
```

---

## 11. Testing Strategy

### Test Profile (`application-test.properties`)

| Config | Value |
|---|---|
| Database | H2 in-memory (`jdbc:h2:mem:testdb`) |
| Liquibase | Enabled — full migration runs on test startup |
| Hibernate DDL | `none` — Liquibase owns schema |
| JWT secret | Fixed test secret (long enough for HMAC-SHA-256) |

### Test Approach

- `@SpringBootTest` — full application context loaded
- `@ActiveProfiles("test")` — switches to H2 and test config
- Liquibase migrations run automatically, ensuring schema matches production
- Services and repositories tested against real H2 schema (no mocks for DB layer)

---

## 12. v2 Roadmap

The following features are planned but not yet implemented:

### 12.1 User Segments & Trait-Based Targeting

Allow SDK clients to send user traits (e.g., `country=VN`, `plan=pro`) and receive flag evaluations filtered by segment rules.

```
Identity (identifier + traits)
  └── evaluated against SegmentRules
        └── determines flag override
```

### 12.2 Percentage Rollout

Deterministic bucketing using MurmurHash3 on `identifier:flagKey`. A user always gets the same bucket, enabling consistent gradual rollouts without storing per-user state.

```
bucket = murmur3(identifier + ":" + flagKey) % 100
flag_enabled = bucket < rollout_percentage
```

### 12.3 Identity & Per-Identity Overrides

Allow specific users to always see a flag as enabled/disabled regardless of segment or rollout rules. Useful for internal testers and account-level exceptions.

### 12.4 Redis Evaluation Cache

Cache `getAllFlags(environmentId)` results in Redis with a short TTL (e.g., 30s). Eliminates per-request DB queries for high-throughput SDK evaluation.

```
SDK Request
  → Redis cache hit? → return cached flags
  → cache miss → query DB → cache result → return flags
```

### 12.5 Audit Log

Record who changed what flag state and when. Append-only `flag_audit_log` table with actor, action, old/new value, and timestamp.

---

## Appendix: Technology Stack

| Category | Technology | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 4.1.0 |
| Security | Spring Security + JJWT | 6.x + 0.12.6 |
| Persistence | Spring Data JPA + Hibernate | — |
| Database | PostgreSQL | 16 |
| Migrations | Liquibase | — |
| Code generation | Lombok + MapStruct | — + 1.6.3 |
| API docs | SpringDoc OpenAPI | 2.8.9 |
| Build | Maven | — |
| Container | Docker + Docker Compose | — |
| Test DB | H2 | — |
