# API Key Default Expiry and Expiry Warnings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keys created through the key API default to a 90-day lifetime, rotation gives the new key a fresh lifetime of the same length, and a daily job warns at 30/7/1 days through Slack and a new `API_KEY_EXPIRING` webhook event.

**Architecture:** Default expiry and rotation lifetime are small changes inside `EnvironmentApiKeyServiceImpl`. Warnings are a new `apikey` package: a `@Scheduled` scanner picks the due threshold per key and hands each key to a separate notifier bean, which claims the threshold with a conditional UPDATE and publishes `ApiKeyExpiringEvent` inside its own transaction, so the existing `AFTER_COMMIT` Slack and webhook listeners deliver it.

**Tech Stack:** Java 21, Spring Boot 4.1 (Data JPA, scheduling, `@TransactionalEventListener`), Liquibase, JUnit 5 + Mockito + AssertJ, H2 in tests.

**Spec:** `docs/superpowers/specs/2026-09-14-api-key-default-expiry-and-warnings-design.md` (extends `docs/superpowers/specs/2026-09-05-api-key-lifecycle-design.md`)

## Global Constraints

- Work in the worktree `C:\Users\ACER\Desktop\aibless\ff-api-key-lifecycle`, branch `feature/api-key-lifecycle`. Run commands from its root in Git Bash with `JAVA_HOME` pointing at JDK 21.
- Default lifetime: **90 days**, property `app.api-key.default-ttl=90d`. Applies **only** to `POST /api/v1/environments/{envId}/api-keys`. Environment creation and cloning keep minting a non-expiring `default` key.
- Opt-out field: `neverExpires` (boolean). `expiresAt` together with `neverExpires` → 400 with message `expiresAt and neverExpires cannot both be set`.
- Rotation: `fresh.expiresAt = now + (old.expiresAt − old.createdAt)`; `NULL` stays `NULL`.
- Thresholds `30,7,1` days; cron `0 0 9 * * *` (server time zone); properties under `app.api-key.expiry-warning.*`.
- Migration number **023** (`021`/`022` belong to `feature/invite-member-by-email`). Never edit an existing changeset file.
- Event type `API_KEY_EXPIRING`. No event, payload, log line or audit row ever carries the plaintext key or `key_hash`.
- Time always comes from the injected `Clock` bean in production code, never `LocalDateTime.now()` without a clock.
- google-java-format via Spotless: run `./mvnw spotless:apply` before every commit.
- Commit messages: Conventional Commits, ending with the attribution lines from the session instructions.

## File map

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/org/aibles/feature_flag/config/ApiKeyProperties.java` (create) | `app.api-key.*` binding: `defaultTtl`, nested `ExpiryWarning` | 1 |
| `src/main/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequest.java` (modify) | `neverExpires` + mutual-exclusion check | 1 |
| `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java` (modify) | Resolve default expiry on create; fresh lifetime on rotate | 1, 2 |
| `src/main/resources/db/changelog/migrations/023-add-api-key-expiry-notice.xml` (create) | `expiry_notice_sent_days` column + `expires_at` index | 3 |
| `src/main/resources/db/changelog/db.changelog-master.xml` (modify) | Include 023 | 3 |
| `src/test/resources/db/changelog/db.changelog-backfill-test.xml` (modify) | Include 023 so the backfill test context matches the entity | 3 |
| `src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java` (modify) | `expiryNoticeSentDays` field | 3 |
| `src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java` (modify) | `findExpiryCandidates`, `claimExpiryNotice` | 3 |
| `src/main/java/org/aibles/feature_flag/notification/event/ApiKeyExpiringEvent.java` (create) | Event record | 4 |
| `src/main/java/org/aibles/feature_flag/domain/enums/WebhookEventType.java` (modify) | `API_KEY_EXPIRING` | 4 |
| `src/main/java/org/aibles/feature_flag/notification/SlackEventListener.java` (modify) | `onApiKeyExpiring` | 4 |
| `src/main/java/org/aibles/feature_flag/webhook/WebhookDispatcher.java` (modify) | `onApiKeyExpiring` | 4 |
| `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifier.java` (create) | Per-key claim + publish, own transaction | 5 |
| `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryScheduler.java` (create) | Daily scan, threshold selection, summary log | 5 |
| `src/main/java/org/aibles/feature_flag/config/ApiKeyExpiryConfig.java` (create) | `@EnableScheduling` when warnings are enabled | 5 |
| `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheck.java` (create) | Startup `WARN` when no channel is active | 6 |
| `src/main/resources/application.properties`, `src/test/resources/application-test.properties` (modify) | New properties | 1, 5 |
| `CLAUDE.md`, `docs/main-flows.md`, `docs/architecture.md`, Postman collection (modify) | Documentation | 7 |

---

### Task 1: Default expiry on create

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/config/ApiKeyProperties.java`
- Create: `src/test/java/org/aibles/feature_flag/config/ApiKeyPropertiesTest.java`
- Modify: `src/main/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequest.java`
- Create: `src/test/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequestTest.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java`
- Modify: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`
- Modify: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java:79-86`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces: `record ApiKeyProperties(Duration defaultTtl, ExpiryWarning expiryWarning)` with nested `record ExpiryWarning(Boolean enabled, String cron, List<Integer> thresholdsDays)`; accessors `defaultTtl()`, `expiryWarning().enabled()`, `expiryWarning().cron()`, `expiryWarning().thresholdsDays()`.
- Produces: `CreateApiKeyRequest#isNeverExpires()` / `setNeverExpires(boolean)`.
- Produces: `EnvironmentApiKeyServiceImpl` constructor gains a 7th argument, `ApiKeyProperties apiKeyProperties`, after `Clock clock`.

- [ ] **Step 1: Write the failing properties test**

Create `src/test/java/org/aibles/feature_flag/config/ApiKeyPropertiesTest.java`:

```java
package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeyPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void appliesTheDesignDefaultsWhenNothingIsConfigured() {
    ApiKeyProperties props = new ApiKeyProperties(null, null);

    assertThat(props.defaultTtl()).isEqualTo(Duration.ofDays(90));
    assertThat(props.expiryWarning().enabled()).isTrue();
    assertThat(props.expiryWarning().cron()).isEqualTo("0 0 9 * * *");
    assertThat(props.expiryWarning().thresholdsDays()).containsExactly(30, 7, 1);
    assertThat(validator.validate(props)).isEmpty();
  }

  @Test
  void rejectsAZeroDefaultTtl() {
    assertThat(validator.validate(new ApiKeyProperties(Duration.ZERO, null)))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("app.api-key.default-ttl must be a positive duration");
  }

  @Test
  void rejectsANonPositiveThreshold() {
    ApiKeyProperties props =
        new ApiKeyProperties(null, new ApiKeyProperties.ExpiryWarning(true, null, List.of(30, 0)));

    assertThat(validator.validate(props))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("app.api-key.expiry-warning.thresholds-days must all be positive");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ApiKeyPropertiesTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class ApiKeyProperties`.

- [ ] **Step 3: Create `ApiKeyProperties`**

Create `src/main/java/org/aibles/feature_flag/config/ApiKeyProperties.java` (registered by the existing `@ConfigurationPropertiesScan` on `FeatureFlagApplication`):

```java
package org.aibles.feature_flag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SDK API key lifecycle configuration, bound from {@code app.api-key.*}.
 *
 * @param defaultTtl lifetime of a key created through {@code POST /api-keys} with neither {@code
 *     expiresAt} nor {@code neverExpires}. Environment creation and cloning do not apply it: those
 *     endpoints give the caller no way to choose a lifetime or opt out.
 * @param expiryWarning the daily scan that warns before a key expires
 */
@ConfigurationProperties(prefix = "app.api-key")
@Validated
public record ApiKeyProperties(Duration defaultTtl, @Valid ExpiryWarning expiryWarning) {

  public ApiKeyProperties {
    defaultTtl = defaultTtl == null ? Duration.ofDays(90) : defaultTtl;
    expiryWarning = expiryWarning == null ? new ExpiryWarning(null, null, null) : expiryWarning;
  }

  @AssertTrue(message = "app.api-key.default-ttl must be a positive duration")
  public boolean isDefaultTtlPositive() {
    return !defaultTtl.isNegative() && !defaultTtl.isZero();
  }

  /**
   * @param enabled master switch, {@code true} when absent
   * @param cron when the scan runs, in the server time zone
   * @param thresholdsDays remaining lifetimes, in days, at which a key is warned — once each
   */
  public record ExpiryWarning(Boolean enabled, String cron, List<Integer> thresholdsDays) {

    public ExpiryWarning {
      enabled = enabled == null ? Boolean.TRUE : enabled;
      cron = cron == null || cron.isBlank() ? "0 0 9 * * *" : cron;
      thresholdsDays =
          thresholdsDays == null || thresholdsDays.isEmpty()
              ? List.of(30, 7, 1)
              : List.copyOf(thresholdsDays);
    }

    @AssertTrue(message = "app.api-key.expiry-warning.thresholds-days must all be positive")
    public boolean isThresholdsPositive() {
      return thresholdsDays.stream().allMatch(days -> days > 0);
    }
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw test -Dtest=ApiKeyPropertiesTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing DTO validation test**

Create `src/test/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequestTest.java`:

```java
package org.aibles.feature_flag.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CreateApiKeyRequestTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private CreateApiKeyRequest request(LocalDateTime expiresAt, boolean neverExpires) {
    CreateApiKeyRequest request = new CreateApiKeyRequest();
    request.setName("ios-app");
    request.setExpiresAt(expiresAt);
    request.setNeverExpires(neverExpires);
    return request;
  }

  @Test
  void rejectsAnExplicitExpiryTogetherWithNeverExpires() {
    assertThat(validator.validate(request(LocalDateTime.now().plusDays(1), true)))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("expiresAt and neverExpires cannot both be set");
  }

  @Test
  void acceptsNeverExpiresOnItsOwn() {
    assertThat(validator.validate(request(null, true))).isEmpty();
  }

  @Test
  void acceptsNeitherFieldSoTheDefaultLifetimeApplies() {
    assertThat(validator.validate(request(null, false))).isEmpty();
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./mvnw test -Dtest=CreateApiKeyRequestTest`
Expected: COMPILATION FAILURE — `cannot find symbol: method setNeverExpires(boolean)`.

- [ ] **Step 7: Add `neverExpires` to the request**

Replace the whole of `src/main/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequest.java` with:

```java
package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CreateApiKeyRequest {

  /** Operator-facing label. Not unique — keys are told apart by prefix and creation time. */
  @NotBlank
  @Size(max = 100)
  private String name;

  /**
   * Planned retirement. When absent the key gets the configured default lifetime ({@code
   * app.api-key.default-ttl}) unless {@link #neverExpires} is set.
   */
  @Future private LocalDateTime expiresAt;

  /** Explicitly create a key that never expires. Cannot be combined with {@code expiresAt}. */
  private boolean neverExpires;

  @AssertTrue(message = "expiresAt and neverExpires cannot both be set")
  public boolean isExpiryUnambiguous() {
    return !(neverExpires && expiresAt != null);
  }
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./mvnw test -Dtest=CreateApiKeyRequestTest`
Expected: PASS (3 tests).

- [ ] **Step 9: Write the failing service tests**

In `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`:

Add imports:

```java
import java.time.Duration;
import org.aibles.feature_flag.config.ApiKeyProperties;
```

Replace the constructor call in `setUp()`:

```java
    service =
        new EnvironmentApiKeyServiceImpl(
            apiKeyRepository,
            environmentRepository,
            permissionService,
            eventPublisher,
            auditService,
            fixedClock,
            new ApiKeyProperties(Duration.ofDays(90), null));
```

Add these tests after `createSucceedsWhenBelowTheCap()`:

```java
  @Test
  void createWithoutAnExpiryAppliesTheDefaultLifetime() {
    service.create(ENV_ID, request("ios", null));

    assertThat(captureSavedKey().getExpiresAt()).isEqualTo(NOW.plusDays(90));
  }

  @Test
  void createWithNeverExpiresStoresNoExpiry() {
    CreateApiKeyRequest request = request("ios-app", null);
    request.setNeverExpires(true);

    service.create(ENV_ID, request);

    assertThat(captureSavedKey().getExpiresAt()).isNull();
  }

  @Test
  void createWithAnExplicitExpiryStoresItUnchanged() {
    service.create(ENV_ID, request("contractor", NOW.plusDays(14)));

    assertThat(captureSavedKey().getExpiresAt()).isEqualTo(NOW.plusDays(14));
  }
```

In `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java`, add the import `import org.aibles.feature_flag.config.ApiKeyProperties;` and replace lines 79-86 with:

```java
    apiKeyService =
        new EnvironmentApiKeyServiceImpl(
            apiKeyRepository,
            environmentRepository,
            permissionService,
            eventPublisher,
            auditService,
            clock,
            new ApiKeyProperties(null, null));
```

- [ ] **Step 10: Run them to verify they fail**

Run: `./mvnw test -Dtest=EnvironmentApiKeyServiceImplTest`
Expected: COMPILATION FAILURE — constructor `EnvironmentApiKeyServiceImpl` cannot be applied to 7 arguments.

- [ ] **Step 11: Resolve the expiry in the service**

In `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java`:

Add the import `import org.aibles.feature_flag.config.ApiKeyProperties;`.

Add the field after `private final Clock clock;`:

```java
  private final ApiKeyProperties apiKeyProperties;
```

In `create(...)`, replace:

```java
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, request.getName(), request.getExpiresAt(), permissionService.currentUserId());
```

with:

```java
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, request.getName(), resolveExpiry(request, now), permissionService.currentUserId());
```

Add this private method directly below `create(...)`:

```java
  /**
   * The stored expiry for a new key: the caller's explicit deadline, {@code null} when the caller
   * asked for a key that never expires, otherwise the configured default lifetime. The request DTO
   * rejects {@code expiresAt} combined with {@code neverExpires} before this runs.
   */
  private LocalDateTime resolveExpiry(CreateApiKeyRequest request, LocalDateTime now) {
    if (request.getExpiresAt() != null) {
      return request.getExpiresAt();
    }
    if (request.isNeverExpires()) {
      return null;
    }
    return now.plus(apiKeyProperties.defaultTtl());
  }
```

- [ ] **Step 12: Add the property**

In `src/main/resources/application.properties`, after the `app.hygiene.evaluation-touch-throttle=5m` line, add:

```properties

# SDK API key lifecycle (2026-09-14 design). A key created via POST /api-keys with neither
# expiresAt nor neverExpires gets this lifetime. Environment creation and cloning do not apply it.
app.api-key.default-ttl=90d
```

- [ ] **Step 13: Run the service tests to verify they pass**

Run: `./mvnw test -Dtest='EnvironmentApiKeyServiceImplTest,EnvironmentServiceImplTest,ApiKeyPropertiesTest,CreateApiKeyRequestTest'`
Expected: PASS, 0 failures.

- [ ] **Step 14: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/config/ApiKeyProperties.java \
  src/main/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequest.java \
  src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java \
  src/main/resources/application.properties \
  src/test/java/org/aibles/feature_flag/config/ApiKeyPropertiesTest.java \
  src/test/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequestTest.java \
  src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java \
  src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java
git commit -m "feat(api): default new API keys to a 90-day lifetime unless neverExpires"
```

---

### Task 2: Rotation keeps the lifetime length

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java` (`rotate(...)`)
- Modify: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`
- Modify: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java`

**Interfaces:**
- Consumes: the 7-argument `EnvironmentApiKeyServiceImpl` constructor from Task 1.
- Produces: no new public API; `rotate(...)` behaviour changes for keys with a non-null `expiresAt`.

- [ ] **Step 1: Replace the inheritance test with the new rule**

In `EnvironmentApiKeyServiceImplTest`, delete the whole test method `rotateCarriesForwardTheOldKeysExpiresAtOntoTheFreshKey()` and add in its place:

```java
  @Test
  void rotateGivesTheFreshKeyAFullLifetimeOfTheSameLength() {
    // A 30-day key created 23 days ago, rotated with 7 days left. Inheriting the old deadline
    // would give the replacement 7 days — the very expiry the operator is rotating to escape.
    EnvironmentApiKey old = activeKey();
    old.setCreatedAt(NOW.minusDays(23));
    old.setExpiresAt(NOW.plusDays(7));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getExpiresAt()).isEqualTo(NOW.plusDays(30));
  }

  @Test
  void rotatingANeverExpiringKeyYieldsANeverExpiringKey() {
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getExpiresAt()).isNull();
  }
```

In `EnvironmentServiceImplTest`, add after `legacyRotateRotatesTheSingleActiveKey()`:

```java
  @Test
  void legacyRotateGivesTheFreshKeyAFullLifetimeOfTheSameLength() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    EnvironmentApiKey theOnlyKey =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .environment(env)
            .name("default")
            .keyHash(ApiKeyHasher.hash("old-key"))
            .createdAt(NOW.minusDays(83))
            .expiresAt(NOW.plusDays(7))
            .build();
    when(apiKeyRepository.findActiveByEnvironmentId(eq(envId), any()))
        .thenReturn(List.of(theOnlyKey));
    when(apiKeyRepository.findById(theOnlyKey.getId())).thenReturn(Optional.of(theOnlyKey));
    when(permissionService.currentUserEmail()).thenReturn("actor@example.com");

    service.rotateApiKey(envId);

    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getExpiresAt()).isEqualTo(NOW.plusDays(90));
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='EnvironmentApiKeyServiceImplTest,EnvironmentServiceImplTest'`
Expected: FAIL — `rotateGivesTheFreshKeyAFullLifetimeOfTheSameLength` expected `2026-10-05T12:00` but was `2026-09-12T12:00`; `legacyRotateGivesTheFreshKeyAFullLifetimeOfTheSameLength` fails the same way.

- [ ] **Step 3: Compute the fresh lifetime**

In `EnvironmentApiKeyServiceImpl`, add the import `import java.time.Duration;`.

In `rotate(...)`, replace:

```java
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, old.getName(), old.getExpiresAt(), permissionService.currentUserId());
```

with:

```java
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, old.getName(), freshExpiry(old, now), permissionService.currentUserId());
```

Add this method directly below `rotate(...)`:

```java
  /**
   * A rotated key gets a fresh lifetime of the same length as the key it replaces — a 30-day key
   * rotates into a 30-day key, a never-expiring key into a never-expiring key. Inheriting the old
   * deadline instead would make rotation useless against an expiring key: the replacement would die
   * on the same day the expiry warning was about.
   */
  private static LocalDateTime freshExpiry(EnvironmentApiKey old, LocalDateTime now) {
    if (old.getExpiresAt() == null) {
      return null;
    }
    return now.plus(Duration.between(old.getCreatedAt(), old.getExpiresAt()));
  }
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest='EnvironmentApiKeyServiceImplTest,EnvironmentServiceImplTest'`
Expected: PASS, 0 failures.

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java \
  src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java \
  src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java
git commit -m "fix(api): give a rotated API key a fresh lifetime instead of the old deadline"
```

---

### Task 3: Migration 023, entity field, and the claim queries

**Files:**
- Create: `src/main/resources/db/changelog/migrations/023-add-api-key-expiry-notice.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `src/test/resources/db/changelog/db.changelog-backfill-test.xml`
- Modify: `src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java`
- Modify: `src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java`
- Modify: `src/test/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepositoryTest.java`

**Interfaces:**
- Produces: `EnvironmentApiKey#getExpiryNoticeSentDays(): Integer`.
- Produces: `List<EnvironmentApiKey> findExpiryCandidates(LocalDateTime now, LocalDateTime horizon)` — environment and project fetched.
- Produces: `int claimExpiryNotice(UUID id, int threshold, LocalDateTime now)` — `@Transactional` (REQUIRED), returns rows updated (0 or 1).

- [ ] **Step 1: Write the failing repository tests**

Append to `EnvironmentApiKeyRepositoryTest` (before the final `}`), adding `import java.util.List;` is not needed — only `EnvironmentApiKey` accessors are used:

```java
  @Test
  void findExpiryCandidatesReturnsOnlyLiveKeysInsideTheHorizon() {
    EnvironmentApiKey inside = save("inside", "exp0cand1", now.plusDays(5), null);
    EnvironmentApiKey never = save("never", "exp0cand2", null, null);
    EnvironmentApiKey beyond = save("beyond", "exp0cand3", now.plusDays(31), null);
    EnvironmentApiKey expired = save("expired", "exp0cand4", now.minusMinutes(1), null);
    EnvironmentApiKey revoked = save("revoked", "exp0cand5", now.plusDays(5), now.minusDays(1));

    // The table is shared with the other tests in this class (no rollback — see the class
    // Javadoc), so assert membership rather than an exact list.
    assertThat(repository.findExpiryCandidates(now, now.plusDays(30)))
        .extracting(EnvironmentApiKey::getId)
        .contains(inside.getId())
        .doesNotContain(never.getId(), beyond.getId(), expired.getId(), revoked.getId());
  }

  @Test
  void findExpiryCandidatesFetchesTheEnvironmentAndItsProject() {
    EnvironmentApiKey key = save("ios", "exp0cand6", now.plusDays(2), null);

    EnvironmentApiKey found =
        repository.findExpiryCandidates(now, now.plusDays(30)).stream()
            .filter(k -> k.getId().equals(key.getId()))
            .findFirst()
            .orElseThrow();

    // The notifier reads these after this query's transaction has closed.
    assertThat(found.getEnvironment().getProject().getName()).isEqualTo("Web");
  }

  @Test
  void claimExpiryNoticeSucceedsOncePerThresholdAndAgainForASmallerOne() {
    EnvironmentApiKey key = save("ios", "exp0clm01", now.plusDays(5), null);

    assertThat(repository.claimExpiryNotice(key.getId(), 7, now)).isEqualTo(1);
    assertThat(repository.claimExpiryNotice(key.getId(), 7, now)).isZero();
    assertThat(repository.claimExpiryNotice(key.getId(), 30, now)).isZero();
    assertThat(repository.claimExpiryNotice(key.getId(), 1, now)).isEqualTo(1);
    assertThat(repository.findById(key.getId()).orElseThrow().getExpiryNoticeSentDays())
        .isEqualTo(1);
  }

  @Test
  void claimExpiryNoticeRefusesARevokedKey() {
    EnvironmentApiKey key = save("ios", "exp0clm02", now.plusDays(5), now.minusMinutes(1));

    assertThat(repository.claimExpiryNotice(key.getId(), 7, now)).isZero();
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=EnvironmentApiKeyRepositoryTest`
Expected: COMPILATION FAILURE — `cannot find symbol: method findExpiryCandidates` / `claimExpiryNotice` / `getExpiryNoticeSentDays`.

- [ ] **Step 3: Create migration 023**

Create `src/main/resources/db/changelog/migrations/023-add-api-key-expiry-notice.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <!--
      Expiry warnings for SDK API keys (docs/superpowers/specs/2026-09-14-api-key-default-expiry-
      and-warnings-design.md). Numbered 023, not 021: 021 and 022 belong to
      feature/invite-member-by-email and have already run against shared databases.
    -->

    <changeSet id="023-1-add-api-key-expiry-notice" author="dev">
        <addColumn tableName="environment_api_key">
            <!-- Smallest warning threshold, in days, already sent for this key. NULL = none yet. -->
            <column name="expiry_notice_sent_days" type="INTEGER"/>
        </addColumn>

        <!-- The daily scan filters on expires_at; 019 indexed only environment_id. -->
        <createIndex tableName="environment_api_key" indexName="idx_environment_api_key_expires_at">
            <column name="expires_at"/>
        </createIndex>

        <rollback>
            <dropIndex tableName="environment_api_key" indexName="idx_environment_api_key_expires_at"/>
            <dropColumn tableName="environment_api_key" columnName="expiry_notice_sent_days"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Include 023 in both top-level changelogs**

In `src/main/resources/db/changelog/db.changelog-master.xml`, after the `020-migrate-api-keys-to-key-table.xml` include, add:

```xml
    <include file="db/changelog/migrations/023-add-api-key-expiry-notice.xml"/>
```

In `src/test/resources/db/changelog/db.changelog-backfill-test.xml`, after its `020-migrate-api-keys-to-key-table.xml` include, add the same line. Without it `ApiKeyBackfillTest`'s context maps `EnvironmentApiKey.expiryNoticeSentDays` onto a table that lacks the column and every key query in that test fails.

- [ ] **Step 5: Add the entity field**

In `EnvironmentApiKey.java`, after the `lastUsedAt` field, add:

```java
  /**
   * Smallest expiry-warning threshold, in days, already sent for this key; {@code null} when none
   * has been. Written only by the conditional UPDATE in {@code
   * EnvironmentApiKeyRepository#claimExpiryNotice}, never through the entity.
   */
  @Column(name = "expiry_notice_sent_days")
  private Integer expiryNoticeSentDays;
```

- [ ] **Step 6: Add the two repository queries**

In `EnvironmentApiKeyRepository.java`, add before `touchLastUsedAt`:

```java
  /**
   * Keys the expiry-warning scan considers: not revoked, not yet expired, and expiring no later than
   * {@code horizon}. Fetches environment and project because the notifier reads their names after
   * this query's transaction has closed.
   */
  @Query(
      "SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment e JOIN FETCH e.project "
          + "WHERE k.revokedAt IS NULL AND k.expiresAt > :now AND k.expiresAt <= :horizon")
  List<EnvironmentApiKey> findExpiryCandidates(
      @Param("now") LocalDateTime now, @Param("horizon") LocalDateTime horizon);

  /**
   * Claims one expiry-warning threshold for one key. Returns 1 when this caller won and must send
   * the warning; 0 when this or a smaller threshold was already claimed (possibly by another
   * instance), or the key was revoked or expired meanwhile. The WHERE clause is what makes the claim
   * safe across instances — do not replace it with a read followed by a write.
   *
   * <p>{@code REQUIRED}, deliberately not {@code REQUIRES_NEW}: it must join the notifier's
   * transaction, so the warning event is published in the same commit as the claim.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE EnvironmentApiKey k SET k.expiryNoticeSentDays = :threshold "
          + "WHERE k.id = :id AND k.revokedAt IS NULL AND k.expiresAt > :now "
          + "AND (k.expiryNoticeSentDays IS NULL OR k.expiryNoticeSentDays > :threshold)")
  int claimExpiryNotice(
      @Param("id") UUID id, @Param("threshold") int threshold, @Param("now") LocalDateTime now);
```

- [ ] **Step 7: Run the repository and migration tests to verify they pass**

Run: `./mvnw test -Dtest='EnvironmentApiKeyRepositoryTest,ApiKeyBackfillTest,NoBackfillFixtureInProductionChangelogTest'`
Expected: PASS, 0 failures.

- [ ] **Step 8: Commit**

```bash
./mvnw spotless:apply
git add src/main/resources/db/changelog/migrations/023-add-api-key-expiry-notice.xml \
  src/main/resources/db/changelog/db.changelog-master.xml \
  src/test/resources/db/changelog/db.changelog-backfill-test.xml \
  src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java \
  src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java \
  src/test/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepositoryTest.java
git commit -m "feat(db): track API key expiry notices with a race-safe claim (migration 023)"
```

---

### Task 4: `ApiKeyExpiringEvent` and its Slack and webhook listeners

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/notification/event/ApiKeyExpiringEvent.java`
- Modify: `src/main/java/org/aibles/feature_flag/domain/enums/WebhookEventType.java`
- Modify: `src/main/java/org/aibles/feature_flag/notification/SlackEventListener.java`
- Modify: `src/main/java/org/aibles/feature_flag/webhook/WebhookDispatcher.java`
- Modify: `src/test/java/org/aibles/feature_flag/notification/SlackEventListenerTest.java`
- Modify: `src/test/java/org/aibles/feature_flag/webhook/WebhookDispatcherTest.java`

**Interfaces:**
- Produces: `record ApiKeyExpiringEvent(UUID environmentId, String environmentName, String projectName, UUID keyId, String keyName, String keyPrefix, LocalDateTime expiresAt, LocalDateTime lastUsedAt, long daysLeft)`.
- Produces: `WebhookEventType.API_KEY_EXPIRING`; `SlackEventListener#onApiKeyExpiring(ApiKeyExpiringEvent)`; `WebhookDispatcher#onApiKeyExpiring(ApiKeyExpiringEvent)`.

- [ ] **Step 1: Write the failing listener tests**

In `SlackEventListenerTest`, add imports `import java.time.LocalDateTime;` and `import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;`, then add:

```java
  private static ApiKeyExpiringEvent expiring(
      String environmentName, LocalDateTime lastUsedAt, long daysLeft) {
    return new ApiKeyExpiringEvent(
        UUID.randomUUID(),
        environmentName,
        "checkout",
        UUID.randomUUID(),
        "nightly-batch",
        "a3f9c1d2",
        LocalDateTime.of(2026, 4, 1, 0, 0),
        lastUsedAt,
        daysLeft);
  }

  @Test
  void apiKeyExpiring_includesKeyDeadlineAndLastUse() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("staging", LocalDateTime.of(2026, 3, 24, 2, 0), 7));

    String msg = capture();
    assertThat(msg).contains("nightly-batch").contains("a3f9c1d2").contains("staging");
    assertThat(msg).contains("checkout").contains("expires in 7 days").contains("2026-04-01 00:00");
    assertThat(msg).contains("Last used 2026-03-24 02:00");
    assertThat(msg).doesNotContain("🔴");
  }

  @Test
  void apiKeyExpiring_neverUsedKey_saysSoAndUsesSingularDay() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("staging", null, 1));

    String msg = capture();
    assertThat(msg).contains("expires in 1 day (").contains("Never used");
  }

  @Test
  void apiKeyExpiring_production_usesCriticalSeverity() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("production", null, 7));

    assertThat(capture()).startsWith("🔴");
  }
```

In `WebhookDispatcherTest`, add imports `import java.time.LocalDateTime;` and `import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;`, then add:

```java
  @Test
  @DisplayName("an API-key-expiring event goes only to the key's environment and carries no secret")
  void apiKeyExpiringIsEnvironmentScopedAndCarriesNoSecret() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.API_KEY_EXPIRING);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onApiKeyExpiring(
        new ApiKeyExpiringEvent(
            envId,
            "production",
            "web",
            UUID.randomUUID(),
            "nightly-batch",
            "a3f9c1d2",
            LocalDateTime.of(2026, 4, 1, 0, 0),
            null,
            7));

    verify(sender)
        .deliver(
            eq(sub),
            org.mockito.ArgumentMatchers.argThat(
                payload ->
                    payload.event() == WebhookEventType.API_KEY_EXPIRING
                        && payload.environmentId().equals(envId.toString())
                        && "nightly-batch".equals(payload.data().get("keyName"))
                        && Long.valueOf(7).equals(payload.data().get("daysLeft"))
                        && !payload.data().containsKey("apiKey")
                        && !payload.data().containsKey("keyHash")));
    verify(environmentRepository, never()).findAllByProjectId(any());
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SlackEventListenerTest,WebhookDispatcherTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ApiKeyExpiringEvent`.

- [ ] **Step 3: Create the event**

Create `src/main/java/org/aibles/feature_flag/notification/event/ApiKeyExpiringEvent.java`:

```java
package org.aibles.feature_flag.notification.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when an active SDK API key crosses an expiry-warning threshold. Never carries the key
 * or its hash. {@code lastUsedAt} is {@code null} when the key has never authenticated; {@code
 * daysLeft} is the remaining lifetime rounded up to whole days.
 */
public record ApiKeyExpiringEvent(
    UUID environmentId,
    String environmentName,
    String projectName,
    UUID keyId,
    String keyName,
    String keyPrefix,
    LocalDateTime expiresAt,
    LocalDateTime lastUsedAt,
    long daysLeft) {}
```

- [ ] **Step 4: Add the webhook event type**

In `WebhookEventType.java`, replace:

```java
  /** An environment's SDK API key was rotated. Environment-scoped. Never carries the key. */
  API_KEY_ROTATED
```

with:

```java
  /** An environment's SDK API key was rotated. Environment-scoped. Never carries the key. */
  API_KEY_ROTATED,
  /**
   * An SDK API key crossed an expiry-warning threshold (30/7/1 days by default).
   * Environment-scoped. Never carries the key. No migration needed: {@code event_type} is {@code
   * VARCHAR(32)} with no CHECK constraint.
   */
  API_KEY_EXPIRING
```

- [ ] **Step 5: Add the Slack listener method**

In `SlackEventListener.java`, add imports `import java.time.format.DateTimeFormatter;` and `import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;`. Add the constant after `private final SlackNotifier slackNotifier;`:

```java
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
```

Add after `onApiKeyRotated(...)`:

```java
  /**
   * The last-used line is what makes this message actionable: recently used means rotate now, long
   * unused means let it expire, never used means revoke it. Rendered as an absolute timestamp so the
   * listener needs no clock.
   */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onApiKeyExpiring(ApiKeyExpiringEvent event) {
    String severity = isProduction(event.environmentName()) ? "🔴 " : "⚠️ ";
    String lastUsed =
        event.lastUsedAt() == null
            ? "Never used."
            : "Last used " + TIMESTAMP.format(event.lastUsedAt()) + ".";
    String message =
        String.format(
            "%s:key: API key \"%s\" (%s…) in *%s* (%s) expires in %d %s (%s). %s",
            severity,
            event.keyName(),
            event.keyPrefix(),
            event.environmentName(),
            event.projectName(),
            event.daysLeft(),
            event.daysLeft() == 1 ? "day" : "days",
            TIMESTAMP.format(event.expiresAt()),
            lastUsed);
    slackNotifier.send(message);
  }
```

- [ ] **Step 6: Add the webhook dispatcher method**

In `WebhookDispatcher.java`, add the import `import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;` and add after `onApiKeyRotated(...)`:

```java
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onApiKeyExpiring(ApiKeyExpiringEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("environmentName", event.environmentName());
    data.put("projectName", event.projectName());
    data.put("keyId", event.keyId() == null ? null : event.keyId().toString());
    data.put("keyName", event.keyName());
    data.put("keyPrefix", event.keyPrefix());
    data.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());
    data.put("lastUsedAt", event.lastUsedAt() == null ? null : event.lastUsedAt().toString());
    data.put("daysLeft", event.daysLeft());
    // Deliberately no key/hash, as with rotation.

    dispatchToEnvironment(WebhookEventType.API_KEY_EXPIRING, event.environmentId(), data);
  }
```

- [ ] **Step 7: Run them to verify they pass**

Run: `./mvnw test -Dtest='SlackEventListenerTest,WebhookDispatcherTest'`
Expected: PASS, 0 failures.

- [ ] **Step 8: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/notification/event/ApiKeyExpiringEvent.java \
  src/main/java/org/aibles/feature_flag/domain/enums/WebhookEventType.java \
  src/main/java/org/aibles/feature_flag/notification/SlackEventListener.java \
  src/main/java/org/aibles/feature_flag/webhook/WebhookDispatcher.java \
  src/test/java/org/aibles/feature_flag/notification/SlackEventListenerTest.java \
  src/test/java/org/aibles/feature_flag/webhook/WebhookDispatcherTest.java
git commit -m "feat(notification): deliver API key expiry warnings to Slack and webhooks"
```

---

### Task 5: The daily scan and the per-key notifier

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifier.java`
- Create: `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryScheduler.java`
- Create: `src/main/java/org/aibles/feature_flag/config/ApiKeyExpiryConfig.java`
- Create: `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifierTest.java`
- Create: `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpirySchedulerTest.java`
- Create: `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryWarningIntegrationTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application-test.properties`

**Interfaces:**
- Consumes: `ApiKeyProperties.expiryWarning().thresholdsDays()` (Task 1); `findExpiryCandidates`, `claimExpiryNotice`, `getExpiryNoticeSentDays` (Task 3); `ApiKeyExpiringEvent` (Task 4).
- Produces: `ApiKeyExpiryNotifier#claimAndWarn(EnvironmentApiKey key, int thresholdDays, LocalDateTime now): boolean`; `static long ApiKeyExpiryNotifier.daysLeft(LocalDateTime now, LocalDateTime expiresAt)`.
- Produces: `ApiKeyExpiryScheduler#scan(): void`; `static Integer ApiKeyExpiryScheduler.dueThreshold(LocalDateTime now, LocalDateTime expiresAt, Integer alreadySentDays, List<Integer> thresholdsDays)`.

- [ ] **Step 1: Write the failing notifier test**

Create `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifierTest.java`:

```java
package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ApiKeyExpiryNotifierTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 25, 9, 0);

  @Mock EnvironmentApiKeyRepository apiKeyRepository;
  @Mock ApplicationEventPublisher eventPublisher;

  private EnvironmentApiKey key() {
    Organization org = Organization.builder().id(UUID.randomUUID()).name("Acme").build();
    Project project =
        Project.builder().id(UUID.randomUUID()).organization(org).name("checkout").build();
    Environment env =
        Environment.builder().id(UUID.randomUUID()).project(project).name("production").build();
    return EnvironmentApiKey.builder()
        .id(UUID.randomUUID())
        .environment(env)
        .name("nightly-batch")
        .keyHash("secret-hash-value")
        .keyPrefix("a3f9c1d2")
        .expiresAt(NOW.plusDays(7))
        .lastUsedAt(NOW.minusHours(17))
        .build();
  }

  @Test
  void publishesNothingWhenTheClaimIsLost() {
    EnvironmentApiKey key = key();
    when(apiKeyRepository.claimExpiryNotice(key.getId(), 7, NOW)).thenReturn(0);

    boolean warned = new ApiKeyExpiryNotifier(apiKeyRepository, eventPublisher).claimAndWarn(key, 7, NOW);

    assertThat(warned).isFalse();
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void publishesTheWarningWithoutKeyMaterialWhenTheClaimIsWon() {
    EnvironmentApiKey key = key();
    when(apiKeyRepository.claimExpiryNotice(key.getId(), 7, NOW)).thenReturn(1);

    boolean warned = new ApiKeyExpiryNotifier(apiKeyRepository, eventPublisher).claimAndWarn(key, 7, NOW);

    assertThat(warned).isTrue();
    ArgumentCaptor<ApiKeyExpiringEvent> captor = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    ApiKeyExpiringEvent event = captor.getValue();
    assertThat(event.environmentId()).isEqualTo(key.getEnvironment().getId());
    assertThat(event.environmentName()).isEqualTo("production");
    assertThat(event.projectName()).isEqualTo("checkout");
    assertThat(event.keyId()).isEqualTo(key.getId());
    assertThat(event.keyName()).isEqualTo("nightly-batch");
    assertThat(event.keyPrefix()).isEqualTo("a3f9c1d2");
    assertThat(event.expiresAt()).isEqualTo(NOW.plusDays(7));
    assertThat(event.lastUsedAt()).isEqualTo(NOW.minusHours(17));
    assertThat(event.daysLeft()).isEqualTo(7);
    assertThat(event.toString()).doesNotContain("secret-hash-value");
  }

  @ParameterizedTest(name = "{0}s left → {1} days")
  @CsvSource({"1, 1", "86400, 1", "86401, 2", "604799, 7", "604800, 7"})
  void daysLeftRoundsUpToWholeDays(long secondsLeft, long expectedDays) {
    assertThat(ApiKeyExpiryNotifier.daysLeft(NOW, NOW.plusSeconds(secondsLeft)))
        .isEqualTo(expectedDays);
  }
}
```

- [ ] **Step 2: Write the failing scheduler test**

Create `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpirySchedulerTest.java`:

```java
package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiKeyExpirySchedulerTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 9, 0);
  private static final List<Integer> THRESHOLDS = List.of(30, 7, 1);

  private EnvironmentApiKeyRepository repository;
  private ApiKeyExpiryNotifier notifier;
  private ApiKeyExpiryScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(EnvironmentApiKeyRepository.class);
    notifier = mock(ApiKeyExpiryNotifier.class);
    Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    scheduler =
        new ApiKeyExpiryScheduler(repository, notifier, new ApiKeyProperties(null, null), clock);
  }

  @ParameterizedTest(name = "{0}h left, already sent {1} → due {2}")
  @CsvSource(
      nullValues = "null",
      value = {
        "600, null, 30", // 25 days, nothing sent yet
        "720, null, 30", // exactly 30 days
        "576, 30, null", // 24 days, 30 already sent
        "169, 30, null", // 7 days + 1 hour: still only inside 30
        "168, 30, 7", // exactly 7 days
        "120, 30, 7", // 5 days after missed scans: warned once, at 7
        "120, 7, null",
        "12, 7, 1",
        "12, 1, null",
        "744, null, null" // 31 days: beyond every threshold
      })
  void dueThreshold(long hoursLeft, Integer alreadySent, Integer expectedDue) {
    assertThat(
            ApiKeyExpiryScheduler.dueThreshold(
                NOW, NOW.plusHours(hoursLeft), alreadySent, THRESHOLDS))
        .isEqualTo(expectedDue);
  }

  @Test
  void scansUpToTheLargestThresholdAndClaimsTheDueOne() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(5)).build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30))).thenReturn(List.of(key));
    when(notifier.claimAndWarn(key, 7, NOW)).thenReturn(true);

    scheduler.scan();

    verify(notifier).claimAndWarn(key, 7, NOW);
  }

  @Test
  void aKeyWithNothingNewDueIsNotClaimed() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .expiresAt(NOW.plusDays(5))
            .expiryNoticeSentDays(7)
            .build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30))).thenReturn(List.of(key));

    scheduler.scan();

    verify(notifier, never()).claimAndWarn(any(), anyInt(), any());
  }

  @Test
  void oneFailingKeyDoesNotStopTheScan() {
    EnvironmentApiKey failing =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(5)).build();
    EnvironmentApiKey next =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(6)).build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30)))
        .thenReturn(List.of(failing, next));
    when(notifier.claimAndWarn(failing, 7, NOW)).thenThrow(new IllegalStateException("boom"));
    when(notifier.claimAndWarn(next, 7, NOW)).thenReturn(true);

    scheduler.scan();

    verify(notifier).claimAndWarn(next, 7, NOW);
  }
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `./mvnw test -Dtest='ApiKeyExpiryNotifierTest,ApiKeyExpirySchedulerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ApiKeyExpiryNotifier` / `ApiKeyExpiryScheduler`.

- [ ] **Step 4: Create the notifier**

Create `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifier.java`:

```java
package org.aibles.feature_flag.apikey;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends one expiry warning for one key, at most once per threshold.
 *
 * <p>Two traps, both pinned by {@code ApiKeyExpiryWarningIntegrationTest}:
 *
 * <ol>
 *   <li>Every notification listener is {@code @TransactionalEventListener(AFTER_COMMIT)} without
 *       {@code fallbackExecution}, so an event published with no active transaction is dropped: no
 *       listener runs, nothing is logged. The event is published inside this method's transaction,
 *       which also means it goes out only once the claim has committed.
 *   <li>This is a separate bean from {@link ApiKeyExpiryScheduler} on purpose. A {@code
 *       @Transactional} method called on {@code this} skips the Spring proxy and runs with no
 *       transaction, which lands straight in trap 1.
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ApiKeyExpiryNotifier {

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Claims {@code thresholdDays} for {@code key} and publishes the warning only if this call won the
   * claim. {@code REQUIRES_NEW} so one key's failure rolls back only its own claim.
   *
   * @return {@code true} when the warning was published
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimAndWarn(EnvironmentApiKey key, int thresholdDays, LocalDateTime now) {
    if (apiKeyRepository.claimExpiryNotice(key.getId(), thresholdDays, now) != 1) {
      return false;
    }
    Environment env = key.getEnvironment();
    eventPublisher.publishEvent(
        new ApiKeyExpiringEvent(
            env.getId(),
            env.getName(),
            env.getProject().getName(),
            key.getId(),
            key.getName(),
            key.getKeyPrefix(),
            key.getExpiresAt(),
            key.getLastUsedAt(),
            daysLeft(now, key.getExpiresAt())));
    return true;
  }

  /** Remaining lifetime rounded up to whole days, so 6 days 23 hours reads as 7. */
  static long daysLeft(LocalDateTime now, LocalDateTime expiresAt) {
    long seconds = Duration.between(now, expiresAt).getSeconds();
    return (seconds + 86_399) / 86_400;
  }
}
```

- [ ] **Step 5: Create the scheduler**

Create `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryScheduler.java`:

```java
package org.aibles.feature_flag.apikey;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily scan for API keys approaching expiry. Runs on every instance; the claim inside {@link
 * ApiKeyExpiryNotifier} keeps each warning single. Registered as a scheduled task only when
 * warnings are enabled (see {@code ApiKeyExpiryConfig}); tests call {@link #scan()} directly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyExpiryScheduler {

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final ApiKeyExpiryNotifier notifier;
  private final ApiKeyProperties properties;
  private final Clock clock;

  @Scheduled(cron = "${app.api-key.expiry-warning.cron:0 0 9 * * *}")
  public void scan() {
    LocalDateTime now = LocalDateTime.now(clock);
    List<Integer> thresholds = properties.expiryWarning().thresholdsDays();
    LocalDateTime horizon = now.plusDays(Collections.max(thresholds));
    int notified = 0;
    int skipped = 0;
    for (EnvironmentApiKey key : apiKeyRepository.findExpiryCandidates(now, horizon)) {
      Integer due =
          dueThreshold(now, key.getExpiresAt(), key.getExpiryNoticeSentDays(), thresholds);
      if (due == null) {
        skipped++;
        continue;
      }
      try {
        if (notifier.claimAndWarn(key, due, now)) {
          notified++;
        } else {
          skipped++;
        }
      } catch (RuntimeException e) {
        // The failed key's own transaction rolled back; keep warning the others.
        log.warn(
            "API key expiry warning failed for key {}: {}",
            key.getId(),
            e.getClass().getSimpleName());
        skipped++;
      }
    }
    log.info("API key expiry scan: {} notified, {} skipped", notified, skipped);
  }

  /**
   * The threshold a key is due for now, or {@code null} when there is nothing new to warn about.
   * The due threshold is the smallest one the remaining lifetime has already dropped to, so a key
   * missed for a few days is warned once, at the threshold that matters now, instead of once for
   * every threshold it skipped.
   */
  static Integer dueThreshold(
      LocalDateTime now,
      LocalDateTime expiresAt,
      Integer alreadySentDays,
      List<Integer> thresholdsDays) {
    Duration remaining = Duration.between(now, expiresAt);
    Integer due =
        thresholdsDays.stream()
            .filter(days -> remaining.compareTo(Duration.ofDays(days)) <= 0)
            .min(Integer::compare)
            .orElse(null);
    if (due == null || (alreadySentDays != null && alreadySentDays <= due)) {
      return null;
    }
    return due;
  }
}
```

- [ ] **Step 6: Run the unit tests to verify they pass**

Run: `./mvnw test -Dtest='ApiKeyExpiryNotifierTest,ApiKeyExpirySchedulerTest'`
Expected: PASS, 0 failures.

- [ ] **Step 7: Enable scheduling and add the properties**

Create `src/main/java/org/aibles/feature_flag/config/ApiKeyExpiryConfig.java`:

```java
package org.aibles.feature_flag.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the API key expiry scan only when warnings are enabled, so the
 * test profile — which disables them — runs no background scheduler thread.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.api-key.expiry-warning",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ApiKeyExpiryConfig {}
```

In `src/main/resources/application.properties`, directly after the `app.api-key.default-ttl=90d` line added in Task 1, add:

```properties
# Daily warning before a key expires, once per threshold, via Slack and API_KEY_EXPIRING webhooks.
# Expiry itself is enforced regardless: configure app.slack or app.webhook before relying on it.
app.api-key.expiry-warning.enabled=true
app.api-key.expiry-warning.cron=0 0 9 * * *
app.api-key.expiry-warning.thresholds-days=30,7,1
```

In `src/test/resources/application-test.properties`, append:

```properties

# API key expiry warnings off in tests: no scheduler thread runs. ApiKeyExpiryWarningIntegrationTest
# calls ApiKeyExpiryScheduler.scan() directly.
app.api-key.expiry-warning.enabled=false
```

- [ ] **Step 8: Write the integration test that pins both traps**

Create `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryWarningIntegrationTest.java`:

```java
package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pins the two traps documented on {@link ApiKeyExpiryNotifier}. If the event were published outside
 * a transaction — either directly from the scheduler, or through a self-invoked {@code
 * @Transactional} method — the AFTER_COMMIT listener below would never run and this test fails.
 * Not {@code @Transactional} itself: the notifier's {@code REQUIRES_NEW} must really commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_keyexpiry;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@Import(ApiKeyExpiryWarningIntegrationTest.RecordingListener.class)
class ApiKeyExpiryWarningIntegrationTest {

  /** Same phase and no fallbackExecution — exactly like the real Slack and webhook listeners. */
  public static class RecordingListener {
    final List<ApiKeyExpiringEvent> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ApiKeyExpiringEvent event) {
      received.add(event);
    }
  }

  @Autowired ApiKeyExpiryScheduler scheduler;
  @Autowired RecordingListener listener;
  @Autowired EnvironmentApiKeyRepository apiKeyRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired OrganizationRepository organizationRepository;

  @Test
  void aScanDeliversOneWarningAfterCommitAndARepeatScanDeliversNone() {
    Organization org =
        organizationRepository.save(
            Organization.builder().name("Acme").slug("acme-" + System.nanoTime()).build());
    Project project =
        projectRepository.save(Project.builder().organization(org).name("checkout").build());
    Environment env =
        environmentRepository.save(
            Environment.builder().project(project).name("production-" + System.nanoTime()).build());
    EnvironmentApiKey key =
        apiKeyRepository.saveAndFlush(
            EnvironmentApiKey.builder()
                .environment(env)
                .name("nightly-batch")
                .keyHash("expiry-it-" + System.nanoTime())
                .keyPrefix("e1a2b3c4")
                .expiresAt(LocalDateTime.now().plusDays(5))
                .build());

    scheduler.scan();

    assertThat(listener.received)
        .filteredOn(event -> event.keyId().equals(key.getId()))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.daysLeft()).isEqualTo(5);
              assertThat(event.projectName()).isEqualTo("checkout");
            });

    scheduler.scan();

    assertThat(listener.received).filteredOn(event -> event.keyId().equals(key.getId())).hasSize(1);
  }
}
```

- [ ] **Step 9: Run the whole task's tests**

Run: `./mvnw test -Dtest='ApiKeyExpiryNotifierTest,ApiKeyExpirySchedulerTest,ApiKeyExpiryWarningIntegrationTest'`
Expected: PASS, 0 failures.

- [ ] **Step 10: Prove the integration test guards trap 1**

Temporarily remove `@Transactional(propagation = Propagation.REQUIRES_NEW)` from `ApiKeyExpiryNotifier.claimAndWarn`, then run:

Run: `./mvnw test -Dtest=ApiKeyExpiryWarningIntegrationTest`
Expected: FAIL — the filtered `received` list is empty (the claim commits through the repository's own transaction, but the event is published with none active and is dropped).

Restore the annotation and rerun; expected PASS.

- [ ] **Step 11: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifier.java \
  src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryScheduler.java \
  src/main/java/org/aibles/feature_flag/config/ApiKeyExpiryConfig.java \
  src/main/resources/application.properties \
  src/test/resources/application-test.properties \
  src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryNotifierTest.java \
  src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpirySchedulerTest.java \
  src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryWarningIntegrationTest.java
git commit -m "feat(api): warn daily before API keys expire, once per threshold"
```

---

### Task 6: Startup warning when no channel can deliver

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheck.java`
- Create: `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheckTest.java`

**Interfaces:**
- Consumes: `ApiKeyProperties.expiryWarning().enabled()` (Task 1); `SlackProperties#isEnabled()`, `#getWebhookUrl()`; `WebhookProperties#enabled()`.
- Produces: `ApiKeyExpiryChannelCheck#checkChannels(): void` (runs on `ApplicationReadyEvent`); package-private `boolean anyChannelActive()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheckTest.java`:

```java
package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.notification.SlackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ApiKeyExpiryChannelCheckTest {

  private static final String WARNING = "keys will expire without notice";

  private static SlackProperties slack(boolean enabled, String url) {
    SlackProperties props = new SlackProperties();
    props.setEnabled(enabled);
    props.setWebhookUrl(url);
    return props;
  }

  private static WebhookProperties webhooks(boolean enabled) {
    return new WebhookProperties(
        enabled,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        3,
        Duration.ofMillis(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        true);
  }

  private static ApiKeyProperties warnings(boolean enabled) {
    return new ApiKeyProperties(null, new ApiKeyProperties.ExpiryWarning(enabled, null, null));
  }

  @Test
  void warnsWhenWarningsAreOnButNoChannelIsActive(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(true), slack(false, null), webhooks(false))
        .checkChannels();

    assertThat(output).contains(WARNING);
  }

  @Test
  void staysQuietWhenSlackIsConfigured(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(
            warnings(true), slack(true, "https://hooks.slack.com/services/x"), webhooks(false))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void staysQuietWhenWebhooksAreEnabled(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(true), slack(false, null), webhooks(true))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void staysQuietWhenWarningsAreDisabled(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(false), slack(false, null), webhooks(false))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void slackEnabledWithoutAUrlDoesNotCountAsActive() {
    assertThat(
            new ApiKeyExpiryChannelCheck(warnings(true), slack(true, " "), webhooks(false))
                .anyChannelActive())
        .isFalse();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ApiKeyExpiryChannelCheckTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class ApiKeyExpiryChannelCheck`.

- [ ] **Step 3: Create the check**

Create `src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheck.java`:

```java
package org.aibles.feature_flag.apikey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.notification.SlackProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes a silent warning pipeline loud. {@code SlackNotifier} and {@code WebhookDispatcher} both
 * return quietly when disabled — right for "a flag was toggled", wrong here: key expiry is enforced
 * with certainty, so a warning that can reach no one means keys die unannounced. Logs once at
 * startup; never blocks it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyExpiryChannelCheck {

  private final ApiKeyProperties apiKeyProperties;
  private final SlackProperties slackProperties;
  private final WebhookProperties webhookProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void checkChannels() {
    if (apiKeyProperties.expiryWarning().enabled() && !anyChannelActive()) {
      log.warn(
          "API key expiry warnings are enabled but no notification channel is active — keys will"
              + " expire without notice. Enable app.slack (with a webhook URL) or app.webhook.");
    }
  }

  boolean anyChannelActive() {
    String url = slackProperties.getWebhookUrl();
    boolean slackActive = slackProperties.isEnabled() && url != null && !url.isBlank();
    return slackActive || webhookProperties.enabled();
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw test -Dtest=ApiKeyExpiryChannelCheckTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheck.java \
  src/test/java/org/aibles/feature_flag/apikey/ApiKeyExpiryChannelCheckTest.java
git commit -m "feat(api): warn at startup when API key expiry warnings have no channel"
```

---

### Task 7: Documentation

**Files:**
- Modify: `CLAUDE.md:134`, `CLAUDE.md:155-163`
- Modify: `docs/main-flows.md:426`, `docs/main-flows.md:488`
- Modify: `docs/architecture.md:95`
- Modify: `docs/postman/Feature_Flag_Platform.postman_collection.json:824-831`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Update `CLAUDE.md`**

Replace line 134:

```markdown
DB schema is managed entirely by Liquibase (`db/changelog/migrations/001–020`, included via `db.changelog-core.xml` for 001–019 plus `020` on top — see below). Never modify a changeset that has already run; always add a new one.
```

with:

```markdown
DB schema is managed entirely by Liquibase (`db/changelog/migrations/001–020` and `023`, included via `db.changelog-core.xml` for 001–019 plus `020` and `023` on top — see below; `021`/`022` belong to `feature/invite-member-by-email`). Never modify a changeset that has already run; always add a new one.
```

After the paragraph ending `...instead of guessing which key "the" key is.` (line 163), add:

```markdown

**Default lifetime and expiry warnings** (`docs/superpowers/specs/2026-09-14-api-key-default-expiry-and-warnings-design.md`).
A key created through `POST .../api-keys` with neither `expiresAt` nor `neverExpires: true` expires
after `app.api-key.default-ttl` (90 days); environment creation and cloning still mint a
non-expiring `default` key. Rotation gives the new key a fresh lifetime of the same length as the
old one (`now + (expiresAt − createdAt)`; never-expiring stays never-expiring) — inheriting the old
deadline would make rotation useless against an expiring key. `ApiKeyExpiryScheduler` scans daily
and `ApiKeyExpiryNotifier` warns once per threshold (30/7/1 days) through Slack and the
`API_KEY_EXPIRING` webhook event, claiming the threshold with a conditional UPDATE on
`expiry_notice_sent_days` (migration `023`). Two rules that are easy to break:

1. **Publish the event inside the notifier's transaction.** The listeners are
   `@TransactionalEventListener(AFTER_COMMIT)` without `fallbackExecution`; an event published with
   no active transaction is dropped silently.
2. **Keep the notifier a separate bean from the scheduler.** A self-invoked `@Transactional` method
   runs with no transaction and falls into rule 1. `ApiKeyExpiryWarningIntegrationTest` pins both.

API key expiry is enforced, unlike flag expiry, which is only reported (`decisions/0028`) — the
difference is intentional.
```

- [ ] **Step 2: Update `docs/main-flows.md`**

At the end of line 426 (the rotate paragraph ending `...giống hệt hành vi cutover cũ.`), append:

```markdown
 Key mới được **vòng đời mới dài bằng vòng đời key cũ** (`now + (expiresAt − createdAt)`): key 30 ngày rotate ra key 30 ngày, key không hết hạn rotate ra key không hết hạn — không thừa hưởng ngày chết của key cũ.
```

Replace line 488:

```markdown
`name` **không cần unique** — trong lúc grace period, key cũ và key mới của cùng một lần rotate cùng mang một tên; phân biệt bằng `keyPrefix` + `createdAt`. `expiresAt` là tuỳ chọn (phải ở tương lai); bỏ trống nghĩa là không bao giờ hết hạn.
```

with:

```markdown
`name` **không cần unique** — trong lúc grace period, key cũ và key mới của cùng một lần rotate cùng mang một tên; phân biệt bằng `keyPrefix` + `createdAt`. `expiresAt` là tuỳ chọn (phải ở tương lai); **bỏ trống thì key hết hạn sau 90 ngày** (`app.api-key.default-ttl`). Muốn key không bao giờ hết hạn phải gửi rõ `"neverExpires": true`; gửi kèm `expiresAt` → 400. Key tạo kèm lúc tạo/clone environment vẫn không hết hạn.

**Cảnh báo trước khi hết hạn.** Mỗi ngày 09:00 (giờ server) job quét key còn **30 / 7 / 1 ngày**, mỗi mốc gửi đúng một lần qua Slack và webhook event `API_KEY_EXPIRING` (payload có `keyName`, `keyPrefix`, `expiresAt`, `lastUsedAt`, `daysLeft` — không bao giờ có key hay hash). Nếu cả Slack lẫn webhook đều tắt, app log `WARN` lúc khởi động: key vẫn chết đúng hạn nhưng không ai được báo.
```

- [ ] **Step 3: Update `docs/architecture.md`**

At the end of line 95, append:

```markdown
 A key created via `POST /api-keys` without `expiresAt` or `neverExpires` gets `app.api-key.default-ttl` (90 days); rotation gives the new key a fresh lifetime of the same length; `ApiKeyExpiryScheduler` + `ApiKeyExpiryNotifier` warn once per threshold (30/7/1 days) through Slack and the `API_KEY_EXPIRING` webhook event.
```

- [ ] **Step 4: Update the Postman collection**

In request `05-07 Create Second API Key (Staging)`, replace the test script lines 824-831:

```json
            "script": { "exec": [
              "pm.test('Status 201', () => pm.response.to.have.status(201));",
              "const body = pm.response.json();",
              "pm.test('Has apiKey (64 chars)', () => pm.expect(body.apiKey).to.have.lengthOf(64));",
              "pm.test('keyPrefix is 8 chars', () => pm.expect(body.key.keyPrefix).to.have.lengthOf(8));",
              "pm.collectionVariables.set('stagingSecondKeyId', body.key.id);",
              "pm.collectionVariables.set('stagingSecondApiKey', body.apiKey);"
            ]}
```

with:

```json
            "script": { "exec": [
              "pm.test('Status 201', () => pm.response.to.have.status(201));",
              "const body = pm.response.json();",
              "pm.test('Has apiKey (64 chars)', () => pm.expect(body.apiKey).to.have.lengthOf(64));",
              "pm.test('keyPrefix is 8 chars', () => pm.expect(body.key.keyPrefix).to.have.lengthOf(8));",
              "pm.test('No expiresAt sent → default lifetime applied', () => pm.expect(body.key.expiresAt).to.be.a('string'));",
              "pm.collectionVariables.set('stagingSecondKeyId', body.key.id);",
              "pm.collectionVariables.set('stagingSecondApiKey', body.apiKey);"
            ]}
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/main-flows.md docs/architecture.md docs/postman/Feature_Flag_Platform.postman_collection.json
git commit -m "docs: default API key lifetime, rotation lifetime and expiry warnings"
```

---

### Task 8: Full verification, then resume the lifecycle plan's Task 7

**Files:** none new.

- [ ] **Step 1: Run the full build**

Run: `./mvnw spotless:apply && ./mvnw verify`
Expected: BUILD SUCCESS — every test passes, `spotless:check` clean, JaCoCo coverage gate met.

If a test outside the files above fails because it now sees a default `expiresAt` on a key created through the key API, fix that test's expectation — do not change the default.

- [ ] **Step 2: Hand off**

Continue with **Task 7 of `docs/superpowers/plans/2026-09-05-api-key-lifecycle.md`** (security review over the whole branch diff, `./mvnw verify`, `/save-memory`, open the PR). In addition to what that task lists, `/save-memory` must record:

- API key expiry is enforced while flag expiry is only reported (`0028`), on purpose.
- The rotation lifetime rule and why deadline inheritance was replaced.
- The two notifier traps (publish inside the transaction; notifier is a separate bean).
- Migration `023` skipped `021`/`022` because another open branch owns them.

---

## Self-Review

**Spec coverage:**
- Default 90-day lifetime, `neverExpires`, 400 on both → Task 1.
- Default not applied to environment create/clone → Global Constraints; untouched call sites; documented in Task 7.
- Rotation lifetime (both endpoints) → Task 2.
- Migration 023 column + index, numbering rationale → Task 3.
- `findExpiryCandidates` / `claimExpiryNotice` → Task 3.
- `ApiKeyExpiringEvent`, `API_KEY_EXPIRING`, Slack message incl. "Never used", webhook payload without secrets → Task 4.
- Scheduler, threshold selection incl. missed days, per-key `REQUIRES_NEW`, continue after failure, INFO summary → Task 5.
- Trap 1 and trap 2 pinned by a test → Task 5 Steps 8-10.
- Test profile disables the scheduler → Task 5 Step 7.
- Startup `WARN` when no channel is active → Task 6.
- Documentation list and memory decision → Tasks 7 and 8.
- Security review → Task 8 hands off to the lifecycle plan's Task 7.

**Deliberate deviation from the spec, reflected back into the spec:** the Slack message renders `lastUsedAt` as an absolute timestamp (`Last used 2026-03-24 02:00.`) instead of a relative "17 hours ago", so `SlackEventListener` keeps its single-argument constructor and needs no `Clock`. The startup check lives in its own `ApiKeyExpiryChannelCheck` bean rather than in `ApiKeyExpiryConfig`, because `ApiKeyExpiryConfig` is conditional on warnings being enabled and the check must be testable without a Spring context.

**Type consistency:** `claimAndWarn(EnvironmentApiKey, int, LocalDateTime)` is used identically in Tasks 5's notifier, scheduler and both tests; `claimExpiryNotice(UUID, int, LocalDateTime)` matches between Task 3 and Task 5; `dueThreshold(LocalDateTime, LocalDateTime, Integer, List<Integer>)` matches its test; `ApiKeyProperties(Duration, ExpiryWarning)` and `ExpiryWarning(Boolean, String, List<Integer>)` match every construction site in Tasks 1, 5 and 6.
