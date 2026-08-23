# 0015 — Structured JSON logging with request correlation (issue #28)

**What:** Per-request correlation id in logs + error bodies, JSON logs in prod / readable in dev.

## Decisions

- **Outermost servlet filter, registered explicitly for ordering.** `logging/RequestCorrelationFilter`
  (a plain `OncePerRequestFilter`, **not** a `@Component`) is wired via a `FilterRegistrationBean`
  in `config/LoggingConfig` at `Ordered.HIGHEST_PRECEDENCE`, url pattern `/*`. Spring Security's
  `FilterChainProxy` is one servlet filter at order **−100** that wraps *all three* internal
  `SecurityFilterChain`s (@Order 0/1/2), so HIGHEST_PRECEDENCE puts correlation before the
  management, SDK, and admin chains alike. Instantiating with `new` (not a bean) avoids Boot's
  default auto-registration, which would place it at LOWEST_PRECEDENCE (after security) and/or
  double-register it.
- **Single MDC choke point.** The filter sets `requestId` (MDC + request attribute + `X-Request-Id`
  response header) and clears the **entire** MDC in a `finally`. The two auth filters add
  `userId` (`JwtAuthenticationFilter`, only when principal `instanceof UserPrincipal`) and `envId`
  (`ApiKeyAuthenticationFilter`) **without** their own cleanup — the outer filter's `finally` is the
  only place MDC is cleared, which is what prevents leakage onto the next request on a pooled thread.
  Keys live in `logging/MdcKeys` (`requestId`/`userId`/`envId`).
- **Incoming `X-Request-Id` is sanitized, not trusted.** Honored only if it matches
  `[A-Za-z0-9_-]{1,64}` (rejects newlines/control chars = log forging, and oversized values);
  otherwise a fresh UUID. Tested.
- **Error bodies carry the id.** `GlobalExceptionHandler.withRequestId()` reads `MDC.get(requestId)`
  and sets it as a `ProblemDetail` property on every handler — **only when non-null**, so errors
  raised outside the filter chain don't emit `"requestId": null`.
- **Profile-based Logback via `logback-spring.xml`** (not `logback.xml`, so `<springProfile>`
  resolves): `prod` → `net.logstash.logback.encoder.LogstashEncoder` with explicit
  `includeMdcKeyName` for the three keys; `!prod` (dev/default) → human-readable pattern with
  `[req=%X{requestId:-}]`. Dep `net.logstash.logback:logstash-logback-encoder:8.0` (runtime scope).
  Prod runs with `--spring.profiles.active=prod`.

## Alternatives considered
- Adding a second post-auth filter to populate userId/envId → rejected; putting the two `MDC.put`s
  directly in the existing auth filters is simpler and keeps cleanup centralized.
- `EndpointRequest`/security DSL for ordering → the servlet-level `FilterRegistrationBean` is the
  correct lever for "before *all* security chains"; per-chain `addFilterBefore` can't precede the
  whole `FilterChainProxy`.

## Verification
- `RequestCorrelationFilterTest` (honor/generate/reject-unsafe/clear-on-throw/**no-leak-across-
  requests**), `GlobalExceptionHandlerTest` (id present / omitted), `LogbackProdEncoderConfigTest`
  (encoder class resolves + prod profile wires it — guards the JSON path that no boot test exercises).
- Full `./mvnw verify`: 195 tests green, Spotless clean, JaCoCo floor (0.83) met. code-reviewer
  agent: no CRITICAL/HIGH/MEDIUM (confirmed ordering, no async/error-dispatch leak, sanitization).

## Note
- No `@Async`/`Callable`/`DeferredResult`/`SseEmitter` in controllers today, so the
  `OncePerRequestFilter` async-dispatch caveat (finally firing before async completes) doesn't
  apply. If async request handling is ever added, revisit MDC propagation.
