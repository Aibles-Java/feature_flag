---
name: spring-security-filter-order-anchor
description: HttpSecurity.addFilterBefore/After must anchor on a registered standard filter (e.g. UsernamePasswordAuthenticationFilter), never on a custom filter — else "does not have a registered order" at context load
metadata:
  type: convention
---

# addFilterBefore/After must anchor on a registered standard filter

`HttpSecurity.addFilterBefore(myFilter, X.class)` / `addFilterAfter(myFilter, X.class)` require
`X` to be a filter Spring Security knows the order of (in its `FilterOrderRegistration`). The
custom filters in this repo — `ApiKeyAuthenticationFilter`, `JwtAuthenticationFilter`, and any new
one — are **not** registered, so anchoring on them throws at context load:

```
IllegalArgumentException: The Filter class ...JwtAuthenticationFilter does not have a registered order
```

...which cascades to `Failed to load ApplicationContext` for every `@SpringBootTest`. (Discovered
in issue #26 when a rate-limit filter was anchored on the custom auth filters.)

**Rule:** anchor on a standard filter such as `UsernamePasswordAuthenticationFilter` (what the
existing chains already use). To order two custom filters relative to each other, exploit the
standard anchor's fixed slot: e.g. `addFilterBefore(a, UPAF.class)` gives slot `UPAF−1` and
`addFilterAfter(b, UPAF.class)` gives `UPAF+1`, so `a` deterministically runs before `b`. Two
filters at the *same* anchor+direction fall back to insertion order (works, but fragile — prefer
distinct offsets when order matters). See [[0009-rate-limiting-bucket4j]].
