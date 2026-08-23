---
name: springboot4-webmvc-test-quirks
description: Spring Boot 4.1 web/MVC test patterns — WebDriverContextCustomizer side-effect, standalone MockMvc, authentication() principal gap
metadata:
  type: feedback
---

Discovered while implementing issue #5 (branch `feature/issue-5-service-repo-controller-tests`).

## 1. `spring-boot-starter-webmvc-test` poisons ALL Spring test contexts

Adding `spring-boot-starter-webmvc-test` to the test classpath registers `WebDriverContextCustomizerFactory` via spring.factories as a **global** `ContextCustomizerFactory`. It injects `WebDriverContextCustomizer` into every Spring test context — including `@SpringBootTest` — changing their cache keys so each test class loads its own context. With multiple `@SpringBootTest` classes all sharing `mem:testdb`, each new context runs Liquibase again → `DATABASECHANGELOG` lock contention.

**Do not add `spring-boot-starter-webmvc-test` to this project's pom.xml.** Use standalone MockMvc instead (§2).

## 2. Controller tests use standalone MockMvc — no Spring context needed

```java
@ExtendWith(MockitoExtension.class)
class XyzControllerTest {
    @Mock XyzService xyzService;
    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new XyzController(xyzService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
    }
}
```

`LocalValidatorFactoryBean.afterPropertiesSet()` activates Bean Validation so `@Valid` annotations work. Without it, validation constraints are silently ignored.

## 3. `SecurityMockMvcRequestPostProcessors.authentication()` does NOT set `request.getUserPrincipal()` in Spring Security 7.x

In Spring Security 7.1.0, `authentication()` saves the `SecurityContext` as a request attribute but does **not** call `request.setUserPrincipal(authentication)`. In standalone MockMvc (no Spring Security filter chain), Spring MVC's `PrincipalMethodArgumentResolver` reads `request.getUserPrincipal()` → gets `null` → the `Authentication authentication` method parameter resolves to null → controller throws NPE → 500.

**Fix for controllers that take `Authentication authentication` as a method parameter** (like `EvaluationController`): use a lambda post-processor that explicitly sets the principal:

```java
private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, Environment env) {
    ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(env);
    return builder.with(request -> { request.setUserPrincipal(auth); return request; });
}
```

This works because `Authentication extends Principal`, so `PrincipalMethodArgumentResolver` returns the `ApiKeyAuthenticationToken` and the controller can call `.getPrincipal()` to get the `Environment`.

See [[springboot4-security-testing]], [[springboot4-jpa-test-quirks]].
