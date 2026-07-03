# 0008 — Secrets externalization: bind-time validation + no-default prod profile (issue #23)

**Date:** 2026-07-04 · **Issue:** #23 (P0 — committed JWT secret)

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
   Also fixed pre-existing `EXPOSE 8080` → `8081` (app's real port).
4. Committed dev defaults stay (acceptance criteria allow them for local compose):
   `local-dev-only-...` JWT key + `ff_user`/`ff_password`; compose parameterized with
   `${POSTGRES_*:-dev-default}`; `.env` gitignored, `.env.example` committed.

## Alternatives considered

- Validation in the record's compact constructor — rejected: breaks direct
  construction in unit tests (negative-expiry case) and loses the bind report.
- `${APP_JWT_SECRET:}` empty-default in prod file — rejected in favor of no default +
  an explicit unresolved-placeholder `@AssertTrue` naming the env var.

Related: [[springboot-configprops-binding-gotchas]], [[jwt-filter-catch-scope]]
