---
name: jwt-filter-catch-scope
description: JWT filters must catch both UsernameNotFoundException and JwtException; use log.warn for security-relevant authentication failures
metadata:
  type: feedback
---

In `JwtAuthenticationFilter.doFilterInternal`, always catch **two** exception types inside the token-resolution block:

```java
} catch (UsernameNotFoundException ex) {
    log.warn("JWT valid but subject no longer exists — request proceeds unauthenticated: {}", ex.getMessage());
    // fall through → 403 at the authorization filter
} catch (JwtException ex) {
    // TOCTOU guard: validateToken() and getEmailFromToken() each parse the token
    // independently; an expiry crossing between the two calls would otherwise escape as 500.
    log.debug("JWT exception after initial validation (possible TOCTOU on expiry): {}", ex.getMessage());
}
```

**Why:** `validateToken()` and `getEmailFromToken()` each call `parseSignedClaims()` separately. A token sitting on the expiry edge can pass `validateToken()` and then throw `ExpiredJwtException` (subclass of `JwtException`) inside `getEmailFromToken()` — the narrow TOCTOU window. Catching only `UsernameNotFoundException` lets this escape as 500.

**Log level:** Use `log.warn` for a valid-JWT/missing-subject event — it is a production-visible security signal (possible token replay after account termination). `log.debug` is only appropriate for routine noise (bad signatures, malformed tokens).

**How to apply:** Any time a JWT filter calls `getEmailFromToken` / `loadUserByUsername` in sequence, add both catch clauses. Do not collapse them into a single `catch (Exception ex)` — `JwtException` and `UsernameNotFoundException` warrant different log levels.

Related: [[springboot4-security-testing]]
