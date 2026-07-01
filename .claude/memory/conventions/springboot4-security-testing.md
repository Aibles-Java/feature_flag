# Spring Boot 4 security-testing gotchas

Discovered while adding security test coverage for issue #4 (branch
`feature/issue-4-security-tests`). Two non-obvious things that will bite anyone writing
tests against this stack (Spring Boot 4.1.0 / Spring Framework 7 / Java 21).

## 1. `@AutoConfigureMockMvc` is gone from its Boot 3 location

In Boot 4.1.0 the package `org.springframework.boot.test.autoconfigure.web.servlet` **does
not exist** — `spring-boot-test-autoconfigure-4.1.0.jar` ships no `MockMvc*` classes at
all. Importing `@AutoConfigureMockMvc` fails to compile.

**How to get a `MockMvc` in an integration test:** build it by hand from the context and
apply the Spring Security configurer:

```java
@SpringBootTest
@ActiveProfiles("test")
class SomeSecurityTest {
    @Autowired WebApplicationContext ctx;
    MockMvc mockMvc;
    @BeforeEach void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx)
            .apply(SecurityMockMvcConfigurers.springSecurity())  // wires the real filter chains
            .build();
    }
}
```

The classic API (`MockMvc`, `MockMvcRequestBuilders`, `MockMvcResultMatchers`,
`MockMvcBuilders`) still lives in `spring-test` 7.x under
`org.springframework.test.web.servlet.**`. `springSecurity()` is in `spring-security-test`
7.1.0. Both are already on the test classpath via `spring-boot-starter-test` +
`spring-security-test`.

Applying `springSecurity()` is what makes both `SecurityFilterChain` beans (SDK API-key,
order=1; Admin JWT, order=2) actually run, so cross-chain isolation can be asserted.

## 2. Don't tamper a JWT by flipping the last signature char

An HS256 signature is 32 bytes → 43 base64url chars with no padding. The **final** char
carries only 2 significant bits; its low 4 bits are ignored on decode. So flipping the last
character (e.g. `A`→`B`) leaves the decoded signature bytes unchanged ~⅓ of the time →
`validateToken` still returns `true` → **flaky test** (passed run 1, failed run 2 here).

Deterministic tamper instead — splice a different payload under the original signature:

```java
String[] a = provider.generateToken(principalA).split("\\.");
String[] b = provider.generateToken(principalB).split("\\.");  // different subject => different payload
String tampered = a[0] + "." + b[1] + "." + a[2];              // HMAC no longer covers the payload
assertThat(provider.validateToken(tampered)).isFalse();        // always fails
```

Same reasoning applies to flipping the last char of the header/payload segments — pick a
mid-segment byte or swap whole segments, never rely on the trailing base64 char.

See [[0004-jacoco-coverage-ratchet-and-ci.md]] — these tests are the first real coverage
landing against that ratchet.
