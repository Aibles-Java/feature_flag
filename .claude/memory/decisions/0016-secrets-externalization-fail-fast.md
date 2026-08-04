# 0016 — Secrets externalization: bind-time validation + no-default prod profile (issue #23)

**Date:** 2026-07-04 (renumbered 2026-07-14 on merge to develop) · **Issue:** #23 (P0 — committed JWT secret)

> Originally authored as `0008`; renumbered to `0016` when the #23 branch was finally merged,
> since develop had meanwhile taken `0008` (hash-sdk-api-keys) through `0015`
> (structured-json-logging). Same policy that produced the 0012 collision — last merge renumbers.

## Decided

1. **Fail-fast mechanism = Bean Validation at ConfigurationProperties binding**
   (`config/JwtProperties` record, `@Validated`). Spring Boot's built-in
   `BindValidationFailureAnalyzer` renders the ops-facing startup report; no custom
   FailureAnalyzer. Rules: `@NotBlank`, `@Positive`, and `@AssertTrue` getters for
   ≥64 UTF-8 bytes (512 bits / HS512), placeholder markers (`change-me`, `changeme`,
   `your-secret`, `placeholder`, `password`, `example`), unresolved `${...}` literal,
   and ≥10 distinct chars (entropy proxy; `openssl rand -hex` draws from 16 so it
   always passes).
2. **`application-prod.properties` uses `${VAR}` placeholders with NO defaults** for
   `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` + `APP_JWT_SECRET`. Rationale: env vars
   already override files via relaxed binding, but a profile file cannot *remove* a
   key — without the no-default override, prod silently falls back to committed dev
   values when an env var is forgotten.
3. **Dockerfile bakes `ENV SPRING_PROFILES_ACTIVE=prod`** (security-review HIGH fix):
   the image is the prod artifact, so containers fail fast by default instead of
   booting on the committed dev JWT key. Chosen over the reviewer's opt-in
   `APP_REQUIRE_PROFILE` guard — an opt-in guard doesn't guard when forgotten.
   (On merge to develop the `EXPOSE 8081` half was dropped as redundant — issue #25/PR #42
   already did `8080`→`8081` and added the `/actuator/health/readiness` HEALTHCHECK; the
   merge keeps that HEALTHCHECK and adds only the `ENV` line.)
4. Committed dev defaults stay (acceptance criteria allow them for local compose):
   `local-dev-only-...` JWT key + `ff_user`/`ff_password`; compose parameterized with
   `${POSTGRES_*:-dev-default}`; `.env` gitignored, `.env.example` committed.
5. **Datasource vars get an explicit startup check too** (`config/RequiredDataSourceEnvPostProcessor`,
   an `EnvironmentPostProcessor` active only under the `prod` profile). PR-review finding: unlike
   `APP_JWT_SECRET` (caught by `JwtProperties`), a missing `SPRING_DATASOURCE_*` var was NOT caught
   with a clear message — the `@ConfigurationProperties` binder passes `${VAR}` through as a literal
   (see [[springboot-configprops-binding-gotchas]]), so `DataSourceProperties` bound the literal and
   the app failed later inside Hikari/Liquibase with an obscure "cannot determine driver" error that
   did not name the variable. The post-processor runs before any bean (guaranteed to win over the
   DataSource/Liquibase beans) and aborts with a message naming each unresolved/blank
   `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`. Chosen over a validated `@ConfigurationProperties`
   record for datasource: bean-creation order vs the autoconfigured DataSource/Liquibase is not
   guaranteed, so a record's validation could lose the race to the obscure error.

## Alternatives considered

- Validation in the record's compact constructor — rejected: breaks direct
  construction in unit tests (negative-expiry case) and loses the bind report.
- `${APP_JWT_SECRET:}` empty-default in prod file — rejected in favor of no default +
  an explicit unresolved-placeholder `@AssertTrue` naming the env var.
- Datasource validation as a second validated `@ConfigurationProperties` record — rejected:
  no ordering guarantee vs the autoconfigured DataSource/Liquibase beans, so the clear error
  could arrive after the obscure Hikari one. An `EnvironmentPostProcessor` runs strictly earlier.

Related: [[springboot-configprops-binding-gotchas]], [[jwt-filter-catch-scope]]
