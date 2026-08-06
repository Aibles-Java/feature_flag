# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Self-hosted Feature Flag management platform (similar to Flagsmith) built on Spring Boot 4.1.0 + Java 21. Supports multi-tenant flag management via Org → Project → Environment hierarchy, with a JWT-secured Admin API and an API-key-secured SDK evaluation API.

## Commands

```bash
# Start infrastructure (PostgreSQL)
docker compose up -d

# Run application
./mvnw spring-boot:run

# Build JAR
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=FeatureFlagServiceTest

# Run a single test method
./mvnw test -Dtest=FeatureFlagServiceTest#shouldCreateFlag
```

After startup:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

## Architecture

### Data model hierarchy
```
Organization → Project → Environment (has API key)
                       → FeatureFlag (has immutable key slug)
                             ↕
                    FlagEnvironmentState (enabled + value per env)
```

`FlagEnvironmentState` is auto-created for every existing environment when a new `FeatureFlag` is created. This means a flag always has exactly one state row per environment — never query flags without joining on this table.

### Three security chains (order matters)

`SecurityConfig` defines three separate `SecurityFilterChain` beans:

0. **Management chain** (`/actuator/**`, `@Order(0)`) — added in issue #29. `/actuator/health` + `/actuator/health/**` are public; everything else (notably `prometheus`, `info`) requires HTTP Basic with role `METRICS` via a local in-memory scraper user. When `app.metrics.password` is blank the account is built `.disabled(true)` to avoid an empty-secret auth bypass.

1. **SDK chain** (`/api/v1/sdk/**`, order=1) — `ApiKeyAuthenticationFilter` reads `X-Environment-Key` header, resolves the `Environment` entity, and sets `ApiKeyAuthenticationToken` as the principal. The resolved `Environment` object is available via `SecurityContextHolder` in `EvaluationController`.

2. **Admin chain** (all other `/api/v1/**`, order=2) — `JwtAuthenticationFilter` validates Bearer tokens. `UserPrincipal` (containing UUID userId) is set as principal.

### Permission model

`PermissionService` is a helper injected into every service impl. It reads the current `UserPrincipal` from `SecurityContextHolder` and checks `OrganizationMember.role` (OWNER / ADMIN / VIEWER) before any mutating operation. Controllers do not contain authorization logic.

### SDK evaluation flow

`EvaluationController` → `EvaluationService.getAllFlags(env)`:
1. Load all `FlagEnvironmentState` for the environment
2. Filter out archived flags
3. Return `[{flagKey, enabled, value, valueType}]`

No identity/segment logic in v1. The `Environment` comes directly from the security principal (no extra DB lookup needed in the controller).

### Flag `key` is immutable

`FeatureFlag.key` is set at creation and must never be updated. It is the stable identifier used by SDKs. `FeatureFlagServiceImpl.update()` intentionally ignores the key field.

## Key configuration

```properties
# application.properties — local-dev defaults only (NOT secrets)
spring.datasource.url=jdbc:postgresql://localhost:5432/feature_flag_db
spring.datasource.username=ff_user
spring.datasource.password=ff_password
spring.jpa.hibernate.ddl-auto=validate   # Liquibase owns the schema
app.jwt.secret=local-dev-only-...        # dev-only signing key
app.jwt.access-expiration-ms=900000       # 15 min
app.jwt.refresh-expiration-ms=1209600000  # 14 days
```

Secrets are externalized via env vars (`APP_JWT_SECRET`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`
— see README.md). The prod profile (`application-prod.properties`) references them with **no
defaults**, so prod can never fall back to dev values. `config/JwtProperties` (typed
`@ConfigurationProperties` + Bean Validation) aborts startup in any profile when the JWT secret
is missing, an unresolved `${...}` placeholder, shorter than 512 bits (64 UTF-8 bytes), or
contains the `change-me` placeholder marker.

DB schema is managed entirely by Liquibase (`db/changelog/migrations/001–007`). Never modify a changeset that has already run; always add a new one.

## API Key generation

`ApiKeyGenerator` uses `SecureRandom` → 32 bytes → `HexFormat.of().formatHex()` → 64-char hex string. This runs on environment creation and on `POST /api/v1/environments/{id}/api-key/rotate`.

## Outbound webhooks (issue #36)

`webhook/` is a second consumer of the same `@Async @TransactionalEventListener(AFTER_COMMIT)`
pipeline as `SlackEventListener` — `WebhookDispatcher` resolves subscriptions, `WebhookSender`
signs and POSTs with retries. Off by default (`app.webhook.enabled=false`).

Two rules that are easy to get wrong:

1. **The webhook secret is encrypted, NOT hashed.** HMAC signing needs the plaintext on every
   delivery, so the `ApiKeyHasher`/SHA-256 precedent used for SDK keys and refresh tokens
   **cannot** be applied here — `SecretCipher` (AES-256-GCM) is reversible on purpose. Its key
   (`APP_WEBHOOK_ENCRYPTION_KEY`) is **not rotatable in place**: changing it orphans every stored
   secret.
2. **`SsrfGuard` runs at subscribe time *and* on every delivery attempt.** DNS is mutable, so a
   subscribe-time-only check is bypassable by rebinding. Don't "optimise" the second check away.

Subscriptions are per-environment; project-scoped events (flag create/update/archive) fan out to
every environment in the project. See `docs/adr/ADR-0005-webhook-delivery-and-secret-storage.md`.

## v2 Roadmap (not yet implemented)

- User Segments + SegmentRules (trait-based targeting)
- Percentage Rollout (MurmurHash3 deterministic bucketing on `identifier:flagKey`)
- Identity & Traits (per-identity flag overrides)
- Redis caching on evaluation results

---

*Sections below generated by /shipwithai-starter on 2026-07-01. Edit directly.*

---

## Project identity

**Name:** feature_flag
**Type:** API (backend service)
**Team size:** 2 developers
**Stage:** Active development

---

## Tech stack

**Language(s):** Java 21
**Framework(s):** Spring Boot 4.1.0 (Web, Data JPA, Security, Validation, Liquibase), JJWT, MapStruct, Lombok, springdoc-openapi
**Build tool:** Maven
**Test framework:** JUnit 5 (spring-boot-starter-test + H2)
**Package manager:** Maven
**Runtime:** Java 21

**Key layers:**
- Controller layer: `src/main/java/org/aibles/feature_flag/controller/` (split into `admin/`, `auth/`, `sdk/`)
- Service layer: `src/main/java/org/aibles/feature_flag/service/` (interfaces + `impl/`)
- Repository layer: `src/main/java/org/aibles/feature_flag/repository/`
- Domain layer: `src/main/java/org/aibles/feature_flag/domain/` (`entity/`, `enums/`)
- Security layer: `src/main/java/org/aibles/feature_flag/security/`
- DTOs: `src/main/java/org/aibles/feature_flag/dto/` (`request/`, `response/`)

---

## Key conventions

**Code style:** google-java-format enforced via Spotless. `./mvnw verify` runs `spotless:check` (CI-gating); `./mvnw spotless:apply` reformats. A Stop hook auto-formats changed files each session.
**Branch strategy:** Gitflow — `feature/<slug>` → `develop`, `release/<version>` → `main` + back to `develop`, `hotfix/<slug>` → `main` + `develop`. Never commit directly to `main`.
**Commit format:** Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `perf:`, `ci:`)
**Test coverage target:** 80%

---

## What Claude should know before touching code

**Sensitive areas (extra care):**
- `security/` and JWT config — two-filter-chain auth design (SDK vs Admin), order matters
- `src/main/resources/db/changelog/migrations/` — never modify an already-run changeset, always add a new one
- `ApiKeyGenerator` / API key rotation endpoint (`SecureRandom`-based)

**Test isolation:**
- Tests use H2; local/integration runs use PostgreSQL via `docker compose up -d`

---

## Development workflow

**When working on any task, Claude must follow these gates:**

- **Plan first:** For any task > 30 min, create a plan and get approval before writing code.
- **Code review:** Run the code-reviewer agent after every significant change. Address all CRITICAL and HIGH findings.
- **Security review:** Before committing to `security/`, JWT config, `db/changelog/migrations/`, or `ApiKeyGenerator`, run a security review. A Stop hook (`security-review-gate.sh`) nudges when these paths change.
- **Migrations are immutable:** A PreToolUse hook (`liquibase-immutable-guard.sh`) blocks edits to existing files under `db/changelog/migrations/` — add a new changeset instead.

**Working a GitHub issue:** follow the `issue-workflow` skill — `.claude/scripts/issue-board.sh start <issue#>` assigns it and moves the Digital banking board card to *In progress*; after opening the PR, `issue-board.sh ready <issue#>` moves it to *Ready For Testing*. A push is **blocked** by the memory gate (`.claude/hooks/pre-push-memory-gate.sh`) if its commits touch code but not `.claude/memory/` — run `/save-memory` first so memory ships with the work (override: `SKIP_MEMORY_CHECK=1 git push`). Enable the git-level backstop once per clone with `git config core.hooksPath .githooks`.

---

## Harness config

**Tier:** Full
**Last updated:** 2026-07-01

Hooks: see `.claude/settings.json`
MCP servers: see `.mcp.json` (none configured yet)
Agents: see `.claude/agents/`
Skills: see `.claude/skills/`
