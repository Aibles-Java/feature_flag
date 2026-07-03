---
name: springboot4-jpa-test-quirks
description: Spring Boot 4.1 JPA/repository test patterns — @DataJpaTest removed, H2 2.4 KEY/VALUE reserved-word quoting, multi-context Liquibase conflict
metadata:
  type: feedback
---

Discovered while implementing issue #5 (branch `feature/issue-5-service-repo-controller-tests`).

## 1. `@DataJpaTest` and `TestEntityManager` are gone in Spring Boot 4.1

`spring-boot-test-autoconfigure-4.1.0.jar` ships no `@DataJpaTest`, `@AutoConfigureTestDatabase`, or `TestEntityManager`. Importing any of these fails to compile.

**Replacement pattern:**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@Transactional
class SomeRepositoryTest {
    @PersistenceContext EntityManager em;
    @Autowired SomeRepository repo;

    private <T> T persist(T entity) { em.persist(entity); return entity; }
    // ...
}
```

Use a **separate in-memory DB name** (e.g. `testdb_jpa`) for `WebEnvironment.NONE` contexts — see §3 below.

## 2. H2 2.4.x: Liquibase auto-quotes `KEY` and `VALUE` as uppercase — Hibernate uses lowercase

Liquibase 5.x with H2 2.4.240 detects `KEY` and `VALUE` as SQL reserved words and emits them as `"KEY"` (uppercase quoted) in DDL:

```sql
CREATE TABLE feature_flags (..., "KEY" VARCHAR(255) NOT NULL, ...)
CREATE TABLE flag_environment_states (..., "VALUE" CLOB, ...)
```

Hibernate (even without `globally_quoted_identifiers=true`) then generates:

```sql
INSERT INTO "feature_flags" ("key", ...) — lowercase "key"
```

H2's case-sensitive identifier matching means `"KEY"` ≠ `"key"` → `Column "key" not found [42122-240]`.

**Fix:** Add to the JPA test H2 URL:
- `NON_KEYWORDS=KEY,VALUE` — prevents H2 treating them as keywords in unquoted contexts
- `CASE_INSENSITIVE_IDENTIFIERS=TRUE` — makes `"KEY"` and `"key"` equivalent

Do NOT add these flags to the main `application-test.properties` — they break Liquibase's own `DATABASECHANGELOG` table detection in other contexts (see §3).

## 3. Multiple `@SpringBootTest` contexts sharing `mem:testdb` → Liquibase fails on second init

When different `@SpringBootTest` configs (e.g. `WebEnvironment.NONE` for repo tests and `WebEnvironment.MOCK` for security integration tests) both connect to the same `mem:testdb`, the second context's Liquibase can't tell `DATABASECHANGELOG` already exists (if `CASE_INSENSITIVE_IDENTIFIERS=TRUE` is in the URL) and tries to `CREATE TABLE DATABASECHANGELOG` → "Table already exists".

**Fix:** Give JPA/repo tests their own database name via `@TestPropertySource`:

```java
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_jpa;...;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
```

Keep `application-test.properties` pointing at `mem:testdb` (without `CASE_INSENSITIVE_IDENTIFIERS`) for the security/smoke integration tests. This way Liquibase runs cleanly in both contexts with no cross-contamination.

**Why:** `CASE_INSENSITIVE_IDENTIFIERS=TRUE` changes how H2 returns table names from `INFORMATION_SCHEMA`, breaking Liquibase's pre-check that normally prevents re-creation of `DATABASECHANGELOG`. Separate DB names eliminate the conflict entirely.

See [[springboot4-security-testing]], [[springboot4-webmvc-test-quirks]].
