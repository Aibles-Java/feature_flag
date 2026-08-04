# feature_flag

Self-hosted feature flag management platform (Spring Boot 4.1 / Java 21, PostgreSQL).
Multi-tenant flag management via an Organization → Project → Environment hierarchy, with
a JWT-secured Admin API and an API-key-secured SDK evaluation API. See
[docs/architecture.md](docs/architecture.md) and [CLAUDE.md](CLAUDE.md) for details.

## Quickstart (local dev)

```bash
docker compose up -d        # PostgreSQL with local-dev defaults
./mvnw spring-boot:run      # app on http://localhost:8081
```

To override any defaults, copy [`.env.example`](.env.example) to `.env` (git-ignored)
and edit it — docker-compose picks it up automatically.

Swagger UI: `http://localhost:8081/swagger-ui.html` · OpenAPI: `http://localhost:8081/api-docs`

Local dev runs on committed, clearly-non-secret defaults (`ff_user`/`ff_password`, a
`local-dev-only-...` JWT signing key). These are for local docker-compose use only.

## Environment variables

Configuration binds env vars via Spring relaxed binding. In the **prod profile**
(`SPRING_PROFILES_ACTIVE=prod`) the variables below are **required** — each one is
referenced without a default in `application-prod.properties`, and a missing one aborts
startup with a message naming it. That naming comes from explicit prod-only startup
checks (`RequiredDataSourceEnvPostProcessor` for the datasource vars, `JwtProperties`
validation for the JWT secret), not from placeholder resolution: the
`@ConfigurationProperties` binder resolves non-strictly, so an unset `${VAR}` would
otherwise slip through as a literal and fail later with an obscure driver error.

| Variable | Required (prod) | Description |
|---|---|---|
| `APP_JWT_SECRET` | yes | JWT signing key. Must be ≥ 64 UTF-8 bytes (512 bits) and not a placeholder — enforced at startup. Generate: `openssl rand -hex 64` |
| `SPRING_DATASOURCE_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://db:5432/feature_flag_db` |
| `SPRING_DATASOURCE_USERNAME` | yes | Database user |
| `SPRING_DATASOURCE_PASSWORD` | yes | Database password |
| `APP_JWT_ACCESS_EXPIRATION_MS` | no | Access-token lifetime in ms (default `900000` = 15 min) |
| `APP_JWT_REFRESH_EXPIRATION_MS` | no | Refresh-token lifetime in ms (default `1209600000` = 14 days) |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | no | docker-compose Postgres overrides (default local-dev values) |

> **Ops migration (issue #32):** `APP_JWT_EXPIRATION_MS` is no longer read — replace it with
> the two variables above. Access tokens now last 15 minutes instead of 24 hours;
> `POST /api/v1/auth/refresh` exchanges a refresh token for a new access token and rotates the
> refresh token itself, and `POST /api/v1/auth/logout` revokes that device's whole token family.
> Reusing an already-rotated refresh token is treated as theft and revokes the family.
> The login response now returns `accessToken`/`refreshToken` — the old `token` field is gone.

**`SPRING_PROFILES_ACTIVE=prod` is a mandatory deployment gate**: without it the app
boots on the committed local-dev defaults, including a publicly known JWT signing key.
The Docker image bakes it in (`ENV` in the Dockerfile), so containerized deployments
fail fast by default; set it explicitly for any non-container deployment.

Startup validation (`JwtProperties`) fails fast — in any profile — when the JWT secret
is missing/blank, shorter than 512 bits, or contains a known placeholder marker
(`change-me`). In the prod profile, `RequiredDataSourceEnvPostProcessor` additionally
aborts startup naming any missing/blank `SPRING_DATASOURCE_*` variable. Never commit real
secrets; docker-compose reads overrides from a git-ignored `.env` file.

## Build & test

```bash
./mvnw verify                          # full suite + JaCoCo coverage gate
./mvnw test -Dtest=SomeTestClass       # single class
./mvnw clean package -DskipTests       # build JAR
```
