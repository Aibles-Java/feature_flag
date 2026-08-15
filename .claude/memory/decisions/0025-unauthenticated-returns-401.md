# 0025 — Unauthenticated admin requests return 401, not 403

**Date:** 2026-08-15
**Status:** Implemented on `feature/unauthenticated-returns-401` (from `develop`, independent of
#48/#49/#50). Security review run per `CLAUDE.md` — no findings at the confidence bar.

## The bug

Every unauthenticated / malformed-token / expired-token request to the admin API returned **403**.

Cause: Spring Security only picks a 401-capable entry point when a **built-in** auth mechanism
(form login, HTTP Basic) is configured. The admin chain authenticates with a custom
`JwtAuthenticationFilter` and configures neither, so `ExceptionHandlingConfigurer` defaulted to
**`Http403ForbiddenEntryPoint`**. Nothing in the code says "403" — it is a silent default.

Impact is not cosmetic: a client cannot distinguish "your session expired, refresh" from "you may
not do this". The SPA's refresh interceptor keyed on 401 (the conventional choice) never fired, and
users silently saw empty pages until reload. Found while wiring the FE refresh flow.

## Fix

`ProblemDetailAuthenticationEntryPoint` → 401 + `WWW-Authenticate: Bearer` + RFC 7807
`application/problem+json`, wired with `.exceptionHandling(...)` on the admin chain only.
Body mirrors `GlobalExceptionHandler` (incl. the MDC `requestId`) and keeps the deliberately
**coarse** detail — the specific reason stays in the correlated log so the endpoint can't be probed
for token/account state.

## The regression this nearly shipped with — MockMvc could not catch it

**The admin chain has no `securityMatcher`, so it is the catch-all — which means it also serves the
container's forward to `/error`.** An unauthenticated `/actuator/**` request is answered by the
management chain with `WWW-Authenticate: Basic`, and the error dispatch then re-enters the *admin*
chain. Writing the header unconditionally **replaced the Basic challenge with `Bearer`**, which
would send a Prometheus scraper at the wrong scheme. Guard: only set the header when absent.

Two lessons, both expensive to relearn:

1. **Found only by diffing a running instance against `develop`**, not by reading code and not by
   the security review (which reasoned statically that the management chain was untouched — it was,
   but the `/error` dispatch was not). Run the thing.
2. **A MockMvc test of this is vacuous** — MockMvc does not perform the container's `/error`
   forward, so the assertion passes with or without the fix. Verified that directly, deleted the
   MockMvc version, and wrote `AuthenticationStatusIntegrationTest` with
   `webEnvironment = RANDOM_PORT` + the JDK `HttpClient`, then **confirmed it goes red when the
   guard is removed**.

Practical notes for that test: `TestRestTemplate` moved packages in Boot 4 — take the port from
`@Value("${local.server.port}")` and use `java.net.http.HttpClient` to sidestep the churn entirely.
And per [[second-springboottest-context-shared-h2]], a distinct `@SpringBootTest` config forks a
second context, so it needs its **own** H2 URL (`mem:authstatusdb`) or Liquibase re-runs.

## Deliberately NOT done

No `AccessDeniedHandler` was registered alongside the entry point. On this chain the only rule is
`anyRequest().authenticated()` and there is no `@PreAuthorize` anywhere, so the filter-level 403
path is **unreachable** — role denials come from `PermissionService` throwing
`UnauthorizedException` via `GlobalExceptionHandler`. An unreachable handler cannot be tested and
would only look like coverage it does not provide (same standard applied in
[[0022-archunit-tier1-governance-gate]]).

## Breaking change

Clients keying on **403 for "not logged in" must move to 401.** 403 now means only "authenticated
but not permitted". The FE branch `feature/be-parity-pagination-refresh-audit` already accepts both,
so it works either way.

## Still open, same defect family

`POST /auth/login` with wrong credentials returns **500**: `authenticationManager.authenticate`
throws `BadCredentialsException` and `GlobalExceptionHandler` has no `AuthenticationException`
handler, so it hits the catch-all. Not fixed here — a different code path (MVC, not the filter
chain) and worth its own change.
