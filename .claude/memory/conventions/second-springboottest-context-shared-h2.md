---
name: second-springboottest-context-shared-h2
description: a @SpringBootTest with distinct properties spins up a SECOND context whose Liquibase run collides on the shared mem:testdb ("DATABASECHANGELOG already exists") — give it its own H2 DB name
metadata:
  type: convention
---

# A second @SpringBootTest context collides on the shared H2 test DB

`application-test.properties` points every test at one persistent in-memory DB
(`jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`). H2 named mem DBs are shared by name for the whole JVM.
As long as all `@SpringBootTest` classes share the *same* configuration, Spring caches **one**
context and Liquibase runs once — fine.

The moment a test adds distinct `@SpringBootTest(properties = {...})` (or other context-config
differences), Spring builds a **second** ApplicationContext that runs Liquibase **again** against
the same `testdb`, which fails at context load:

```
liquibase ... Table "databasechangelog" already exists
→ Failed to load ApplicationContext (cascades to every integration test in the run)
```

Discovered in issue #26: adding `RateLimitIntegrationTest` (which overrides `app.rate-limit.*`)
broke the whole suite even though it passed standalone.

**Fix:** give the divergent test class its own in-memory DB via a datasource-URL override, so its
Liquibase run is independent:

```java
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ratelimit-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    ... // the properties that made this a distinct context
})
```

Related test gotchas: [[springboot4-security-testing]], [[sdk-eval-key-column-h2-500]].
