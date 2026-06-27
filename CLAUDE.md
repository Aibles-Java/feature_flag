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

### Two security chains (order matters)

`SecurityConfig` defines two separate `SecurityFilterChain` beans:

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
# application.properties
spring.datasource.url=jdbc:postgresql://localhost:5432/feature_flag_db
spring.datasource.username=ff_user
spring.datasource.password=ff_password
spring.jpa.hibernate.ddl-auto=validate   # Liquibase owns the schema
app.jwt.secret=<min 512-bit secret>
app.jwt.expiration-ms=86400000
```

DB schema is managed entirely by Liquibase (`db/changelog/migrations/001–007`). Never modify a changeset that has already run; always add a new one.

## API Key generation

`ApiKeyGenerator` uses `SecureRandom` → 32 bytes → `HexFormat.of().formatHex()` → 64-char hex string. This runs on environment creation and on `POST /api/v1/environments/{id}/api-key/rotate`.

## v2 Roadmap (not yet implemented)

- User Segments + SegmentRules (trait-based targeting)
- Percentage Rollout (MurmurHash3 deterministic bucketing on `identifier:flagKey`)
- Identity & Traits (per-identity flag overrides)
- Redis caching on evaluation results
