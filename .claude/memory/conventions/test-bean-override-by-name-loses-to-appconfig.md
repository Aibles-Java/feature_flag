---
name: test-bean-override-by-name-loses-to-appconfig
description: redefining a production @Bean under the same name in a test-level @Import loses to the application's own definition — swap instances with a BeanPostProcessor (or @TestBean/@MockitoBean) instead
metadata:
  type: convention
---

# A same-name @Bean in a test @Import loses to the application's definition

`FixedClocksTestConfig` (PR #156, S-2.12) pinned the `clock` and `changeWindowClock` beans by
redefining them under the same names with `spring.main.allow-bean-definition-overriding=true`.
With overriding on, the definition registered **last** wins — and a test-level `@Import` is
registered **before** the component-scanned `AppConfig`. The real system clocks won silently.

Every change-window test then depended on wall-clock time: green between 09:00 and 17:00 UTC
(the fixture's prod window), red outside it. PR CI happened to run inside the window, so #156
merged green and the first `develop` push (07:51 UTC, 2026-10-18) failed 6 tests and skipped
publish + deploy.

**Rule:** to replace a production bean in a test, never rely on same-name redefinition order.
Use a `static` `BeanPostProcessor` that swaps the instance by bean name (what
`FixedClocksTestConfig` does now), or `@TestBean` / `@MockitoBean` (see
[[springboot4-mockito-spy-bean]]). And when a test is time-dependent, run it once **outside**
the window it assumes — a fixed clock that isn't actually wired looks identical to one that is
for half the day.
