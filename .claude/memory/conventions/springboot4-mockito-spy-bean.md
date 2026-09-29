---
name: springboot4-mockito-spy-bean
description: Spring Boot 4.x removes @SpyBean/@MockBean — use @MockitoSpyBean/@MockitoBean from spring-test instead
metadata:
  type: feedback
---

In Spring Boot 4.x (Spring Framework 7), `@SpyBean` and `@MockBean` from `org.springframework.boot.test.mock.mockito` are **removed** (deprecated in Boot 3.4, gone in Boot 4).

**Use instead:**
- `@MockitoSpyBean` → `org.springframework.test.context.bean.override.mockito.MockitoSpyBean`
- `@MockitoBean` → `org.springframework.test.context.bean.override.mockito.MockitoBean`

These are part of `spring-test` (Spring Framework 6.2+), not the Boot test starter.

**Why:** Discovered when writing `EvaluationCacheIntegrationTest` (issue #30). The old import compiled fine in Boot 3.x but `package org.springframework.boot.test.mock.mockito does not exist` in Boot 4.1.

**How to apply:** Any `@SpringBootTest` integration test that needs a spy or mock on a real Spring bean must use the new annotations.
