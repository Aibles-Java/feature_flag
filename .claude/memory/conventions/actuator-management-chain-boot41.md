# Actuator management security chain on Spring Boot 4.1 — two gotchas

Discovered wiring `/actuator/prometheus` behind auth in issue #29 (see [[0012-micrometer-prometheus-metrics]]).

## 1. `EndpointRequest` moved modules in Boot 4.1 — use path matchers

`org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest` (the
`EndpointRequest.toAnyEndpoint()` / `to(HealthEndpoint.class)` helper) **no longer exists** at
that path in Boot 4.1. It relocated to the separate `spring-boot-security` module
(`org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest`), and
`HealthEndpoint` moved too — so the old imports fail to compile with
`package ... does not exist`.

Rather than chase the new modules, match on the **fixed base path**: `securityMatcher("/actuator/**")`
and `requestMatchers("/actuator/health", "/actuator/health/**")`. Safe because we don't override
`management.endpoints.web.base-path` (defaults to `/actuator`). Keeps the chain dependency-light.

## 2. Blank shared-secret + `{noop}` = auth bypass, not "unusable"

An in-memory Basic-auth account built as `User.withUsername(u).password("{noop}" + pw)` where `pw`
comes from a config property that **defaults to blank** is NOT closed-by-default. `NoOpPasswordEncoder`
does `raw.equals(stored)`, and empty-vs-empty is `true`, so an attacker sending `u:` (empty password)
authenticates. Anyone who reasons "no password configured ⇒ nobody can log in" is wrong.

Fix: when the secret is blank, build the account **`.disabled(true)`** — a disabled `UserDetails`
throws `DisabledException` on every attempt, so the endpoint is genuinely closed until an operator
sets the secret (here `APP_METRICS_PASSWORD`). Guard it with a test that sends empty Basic creds and
asserts 401. Applies to any `InMemoryUserDetailsManager` account fed by an overridable-but-blank
config value.
