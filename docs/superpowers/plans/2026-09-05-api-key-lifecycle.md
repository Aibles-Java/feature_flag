# API Key Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the SDK API key off `environments.api_key_hash` into its own entity so an environment can hold several keys, each with an optional expiry and its own revocation state.

**Architecture:** A new `environment_api_key` table owns the credential; `Environment` keeps none. `ApiKeyAuthenticationFilter` resolves the key row (not the environment) and rejects revoked or expired keys; the SDK principal becomes `EnvironmentApiKey`, which exposes `getEnvironment()`. Key lifecycle lives in its own service rather than growing `EnvironmentServiceImpl`. Rotation gains a grace period so an SDK fleet migrates without downtime.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Liquibase, JUnit 5 + Mockito + AssertJ, H2 (tests) / PostgreSQL (runtime).

**Spec:** `docs/superpowers/specs/2026-09-05-api-key-lifecycle-design.md`

## Global Constraints

- **Migration number is `019`.** Never edit an existing changeset — a PreToolUse hook (`liquibase-immutable-guard.sh`) blocks it. Register the file in `db.changelog-master.xml`.
- **`spring.jpa.hibernate.ddl-auto=validate`** — Liquibase owns the schema. Any entity field without a matching column fails startup, so migration and entity land in the same task.
- **Formatting is CI-gating.** Run `./mvnw spotless:apply` before every commit; `./mvnw verify` runs `spotless:check`.
- **Timestamps are `LocalDateTime` mapped to `TIMESTAMPTZ`**, matching every existing entity.
- **A key is _active_ when** `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now)`. This single predicate decides authentication, the per-environment cap, and the legacy rotate endpoint's target.
- **Never log, audit, or return the plaintext key or its hash.** Audit rows for key events carry `before`/`after` as `null`, extending the existing rule in `EnvironmentServiceImpl.rotateApiKey()`.
- **Time comes from the injected `Clock` bean** (`AppConfig.clock()`), never `LocalDateTime.now()`, in any class that evaluates expiry.
- **Commit format:** Conventional Commits. Branch is `feature/api-key-lifecycle`, already checked out.

---

### Task 1: `EnvironmentApiKey` entity, repository, and the table

Creates the table and the entity that maps it. Nothing reads from it yet — `environments.api_key_hash` is still the live authentication source, so the build and every existing test stay green.

**Files:**
- Create: `src/main/resources/db/changelog/migrations/019-create-environment-api-keys.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java`
- Create: `src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java`
- Test: `src/test/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKeyTest.java`
- Test: `src/test/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepositoryTest.java`

**Interfaces:**
- Produces: `EnvironmentApiKey` with `isActive(Clock)`, `isExpired(Clock)`, `isRevoked()`, `getEnvironment()`.
- Produces: `EnvironmentApiKeyRepository.findActiveByKeyHash(String)`, `.findByKeyHash(String)`, `.findAllByEnvironmentId(UUID, Pageable)`, `.countActiveByEnvironmentId(UUID, LocalDateTime)`, `.touchLastUsedAt(UUID, LocalDateTime, LocalDateTime)`, `.findLastUsedAtByEnvironmentId(UUID)`.

- [ ] **Step 1: Write the failing entity test**

Create `src/test/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKeyTest.java`:

```java
package org.aibles.feature_flag.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The active/expired/revoked predicate is the single rule that decides authentication, the
 * per-environment cap and the legacy rotate endpoint's target, so it is pinned here directly
 * rather than only through the callers.
 */
class EnvironmentApiKeyTest {

  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

  private static Clock fixedAt(LocalDateTime moment) {
    return Clock.fixed(moment.atZone(ZONE).toInstant(), ZONE);
  }

  private static Clock now() {
    return fixedAt(NOW);
  }

  @Test
  void keyWithNoExpiryAndNoRevocationIsActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().build();

    assertThat(key.isActive(now())).isTrue();
    assertThat(key.isExpired(now())).isFalse();
    assertThat(key.isRevoked()).isFalse();
  }

  @Test
  void keyExpiringInTheFutureIsStillActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW.plusSeconds(1)).build();

    assertThat(key.isActive(now())).isTrue();
  }

  @Test
  void keyIsExpiredAtTheExactExpiryInstant() {
    // The boundary is closed: expires_at is the first instant the key no longer works, so a
    // caller cannot squeeze a request through on the tick itself.
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW).build();

    assertThat(key.isExpired(now())).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void keyPastItsExpiryIsNotActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW.minusSeconds(1)).build();

    assertThat(key.isExpired(now())).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void revokedKeyIsNotActiveEvenWithNoExpiry() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().revokedAt(NOW.minusDays(1)).build();

    assertThat(key.isRevoked()).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void revocationWinsOverAFutureExpiry() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().expiresAt(NOW.plusDays(30)).revokedAt(NOW).build();

    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void expiryIsEvaluatedAgainstTheSuppliedClockNotWallTime() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW).build();

    assertThat(key.isActive(fixedAt(NOW.minusHours(1)))).isTrue();
    assertThat(key.isActive(fixedAt(NOW.plusHours(1)))).isFalse();
  }

  @Test
  void fixedClockInstantIsRespected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneId.of("UTC"));
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().expiresAt(LocalDateTime.of(2026, 9, 5, 11, 59)).build();

    assertThat(key.isActive(clock)).isFalse();
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw test -Dtest=EnvironmentApiKeyTest`
Expected: FAIL — compilation error, `EnvironmentApiKey` does not exist.

- [ ] **Step 3: Write the entity**

Create `src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java`:

```java
package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One SDK credential for one environment. An environment may hold several, which is what lets a key
 * be rotated with a grace period instead of a hard cutover, and lets one leaked consumer's key be
 * withdrawn without cutting off the others.
 *
 * <p>The {@code expiresAt}/{@code revokedAt} pair mirrors {@link RefreshToken} so the two credential
 * lifecycles in this codebase read the same way.
 */
@Entity
@Table(name = "environment_api_key")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvironmentApiKey {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id", nullable = false)
  private Environment environment;

  /** Operator-facing label ("ios-app", "nightly-batch"). Deliberately not unique — see the spec. */
  @Column(nullable = false, length = 100)
  private String name;

  /** SHA-256 hash (lowercase hex) of the key. The plaintext is never stored. */
  @Column(name = "key_hash", nullable = false, unique = true, length = 64)
  private String keyHash;

  /**
   * First 8 characters of the plaintext. Not a secret: with the plaintext shown only once, this is
   * the only way an operator can tell which row corresponds to which deployed config. Empty for
   * rows backfilled by migration 019, whose plaintext was never recoverable.
   */
  @Column(name = "key_prefix", nullable = false, length = 8)
  @Builder.Default
  private String keyPrefix = "";

  /** {@code null} means the key never expires. */
  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  /** {@code null} means the key has not been revoked. Revocation is permanent. */
  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  /** Last time this key successfully authenticated an SDK request. Coarse — throttled in filter. */
  @Column(name = "last_used_at")
  private LocalDateTime lastUsedAt;

  /** Nulled rather than cascaded: the key outlives the person who minted it. */
  @Column(name = "created_by")
  private UUID createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public boolean isRevoked() {
    return revokedAt != null;
  }

  /** The boundary is closed: a key is expired at the exact instant named by {@code expiresAt}. */
  public boolean isExpired(Clock clock) {
    return expiresAt != null && !LocalDateTime.now(clock).isBefore(expiresAt);
  }

  public boolean isActive(Clock clock) {
    return !isRevoked() && !isExpired(clock);
  }
}
```

- [ ] **Step 4: Run the entity test — it still fails on schema validation? No: it is a pure unit test**

Run: `./mvnw test -Dtest=EnvironmentApiKeyTest`
Expected: PASS. This test constructs the entity directly and never touches the database, so it passes before the table exists.

- [ ] **Step 5: Write the migration**

Create `src/main/resources/db/changelog/migrations/019-create-environment-api-keys.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <!--
      Multiple SDK API keys per environment, each with its own expiry and revocation state.
      Step 1 of 3 creates the table; the backfill and the drop of environments.api_key_hash
      follow in the next task, so this changeset alone changes no behaviour.
    -->

    <changeSet id="019-1-create-environment-api-key" author="dev">
        <createTable tableName="environment_api_key">
            <column name="id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="environment_id" type="UUID">
                <constraints nullable="false"
                             foreignKeyName="fk_environment_api_key_environment"
                             references="environments(id)"
                             deleteCascade="true"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="key_hash" type="VARCHAR(64)">
                <constraints nullable="false" unique="true"
                             uniqueConstraintName="uq_environment_api_key_hash"/>
            </column>
            <column name="key_prefix" type="VARCHAR(8)" defaultValue="">
                <constraints nullable="false"/>
            </column>
            <column name="expires_at" type="TIMESTAMPTZ"/>
            <column name="revoked_at" type="TIMESTAMPTZ"/>
            <column name="last_used_at" type="TIMESTAMPTZ"/>
            <!--
              No FK to users(id): a deleted user must not take their environment's live SDK
              credential down with them, and Liquibase's addForeignKeyConstraint SET NULL
              behaviour is not expressible inline here. The column is advisory metadata.
            -->
            <column name="created_by" type="UUID"/>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex tableName="environment_api_key" indexName="idx_environment_api_key_env">
            <column name="environment_id"/>
        </createIndex>

        <rollback>
            <dropTable tableName="environment_api_key"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 6: Register it in the master changelog**

In `src/main/resources/db/changelog/db.changelog-master.xml`, add after the `018` line:

```xml
    <include file="db/changelog/migrations/019-create-environment-api-keys.xml"/>
```

- [ ] **Step 7: Write the failing repository test**

Create `src/test/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepositoryTest.java`. Open `src/test/java/org/aibles/feature_flag/repository/FlagEnvironmentStateRepositoryTest.java` first and copy its `@DataJpaTest` setup and entity-graph fixture style verbatim — it already builds the Organization → Project → Environment chain this test needs.

```java
package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class EnvironmentApiKeyRepositoryTest {

  @Autowired private EnvironmentApiKeyRepository repository;
  @Autowired private EnvironmentRepository environmentRepository;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private OrganizationRepository organizationRepository;

  private Environment environment;
  private LocalDateTime now;

  @BeforeEach
  void setUp() {
    now = LocalDateTime.of(2026, 9, 5, 12, 0);
    Organization org =
        organizationRepository.save(Organization.builder().name("Acme").slug("acme").build());
    Project project =
        projectRepository.save(Project.builder().organization(org).name("Web").build());
    environment =
        environmentRepository.save(Environment.builder().project(project).name("prod").build());
  }

  private EnvironmentApiKey save(String name, String hash, LocalDateTime expiresAt,
      LocalDateTime revokedAt) {
    return repository.save(
        EnvironmentApiKey.builder()
            .environment(environment)
            .name(name)
            .keyHash(hash)
            .keyPrefix(hash.substring(0, 8))
            .expiresAt(expiresAt)
            .revokedAt(revokedAt)
            .build());
  }

  @Test
  void findActiveByKeyHashReturnsALiveKey() {
    save("ios", "aaaa1111", null, null);

    assertThat(repository.findActiveByKeyHash("aaaa1111", now)).isPresent();
  }

  @Test
  void findActiveByKeyHashSkipsARevokedKey() {
    save("ios", "bbbb2222", null, now.minusDays(1));

    assertThat(repository.findActiveByKeyHash("bbbb2222", now)).isEmpty();
    // findByKeyHash still returns it — the filter needs the row to explain *why* it failed.
    assertThat(repository.findByKeyHash("bbbb2222")).isPresent();
  }

  @Test
  void findActiveByKeyHashSkipsAnExpiredKey() {
    save("ios", "cccc3333", now.minusMinutes(1), null);

    assertThat(repository.findActiveByKeyHash("cccc3333", now)).isEmpty();
  }

  @Test
  void findByKeyHashEagerlyInitializesTheEnvironment() {
    save("ios", "dddd4444", null, null);

    EnvironmentApiKey found = repository.findByKeyHash("dddd4444").orElseThrow();

    // The SDK filter runs outside a transaction, so a lazy proxy here would blow up in the
    // controller. The JOIN FETCH is the thing under test.
    assertThat(found.getEnvironment().getName()).isEqualTo("prod");
  }

  @Test
  void countActiveByEnvironmentIdCountsOnlyLiveKeys() {
    save("a", "1111aaaa", null, null);
    save("b", "2222bbbb", now.plusDays(1), null);
    save("c", "3333cccc", now.minusDays(1), null); // expired
    save("d", "4444dddd", null, now); // revoked

    assertThat(repository.countActiveByEnvironmentId(environment.getId(), now)).isEqualTo(2);
  }

  @Test
  void touchLastUsedAtIsANoOpInsideTheThresholdWindow() {
    EnvironmentApiKey key = save("ios", "5555eeee", null, null);
    LocalDateTime recent = now.minusMinutes(1);
    repository.touchLastUsedAt(key.getId(), recent, now.minusMinutes(5));

    repository.touchLastUsedAt(key.getId(), now, now.minusMinutes(5));

    assertThat(repository.findById(key.getId()).orElseThrow().getLastUsedAt()).isEqualTo(recent);
  }

  @Test
  void touchLastUsedAtWritesWhenTheStampIsOlderThanTheThreshold() {
    EnvironmentApiKey key = save("ios", "6666ffff", null, null);
    repository.touchLastUsedAt(key.getId(), now.minusHours(1), now.minusMinutes(5));

    repository.touchLastUsedAt(key.getId(), now, now.minusMinutes(5));

    assertThat(repository.findById(key.getId()).orElseThrow().getLastUsedAt()).isEqualTo(now);
  }

  @Test
  void findLastUsedAtByEnvironmentIdReturnsTheMostRecentAcrossKeys() {
    EnvironmentApiKey older = save("a", "7777aaaa", null, null);
    EnvironmentApiKey newer = save("b", "8888bbbb", null, null);
    repository.touchLastUsedAt(older.getId(), now.minusDays(2), now.minusYears(1));
    repository.touchLastUsedAt(newer.getId(), now.minusHours(3), now.minusYears(1));

    assertThat(repository.findLastUsedAtByEnvironmentId(environment.getId()))
        .contains(now.minusHours(3));
  }

  @Test
  void findLastUsedAtByEnvironmentIdIsEmptyWhenNoKeyWasEverUsed() {
    save("a", "9999aaaa", null, null);

    assertThat(repository.findLastUsedAtByEnvironmentId(environment.getId())).isEmpty();
  }

  @Test
  void findAllByEnvironmentIdPagesKeysNewestFirst() {
    save("a", "aaaa0001", null, null);
    save("b", "aaaa0002", null, null);

    assertThat(repository.findAllByEnvironmentId(environment.getId(), PageRequest.of(0, 10)))
        .hasSize(2);
  }

  @Test
  void keysOfOtherEnvironmentsAreNotCounted() {
    save("a", "bbbb0001", null, null);

    assertThat(repository.countActiveByEnvironmentId(UUID.randomUUID(), now)).isZero();
  }
}
```

- [ ] **Step 8: Run it and confirm it fails**

Run: `./mvnw test -Dtest=EnvironmentApiKeyRepositoryTest`
Expected: FAIL — `EnvironmentApiKeyRepository` does not exist.

- [ ] **Step 9: Write the repository**

Create `src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java`:

```java
package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EnvironmentApiKeyRepository extends JpaRepository<EnvironmentApiKey, UUID> {

  /**
   * Authentication lookup. Fetches the environment in the same query: the SDK filter runs outside a
   * transaction, so a lazy proxy would fail on first access in the controller.
   *
   * <p>Returns the row regardless of validity — the filter distinguishes "revoked" from "expired"
   * from "unknown" for the response message, which it cannot do if the predicate is in the WHERE
   * clause.
   */
  @Query("SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment WHERE k.keyHash = :hash")
  Optional<EnvironmentApiKey> findByKeyHash(@Param("hash") String hash);

  /** The same lookup narrowed to active keys — used where the reason for rejection is irrelevant. */
  @Query(
      "SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment "
          + "WHERE k.keyHash = :hash AND k.revokedAt IS NULL "
          + "AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
  Optional<EnvironmentApiKey> findActiveByKeyHash(
      @Param("hash") String hash, @Param("now") LocalDateTime now);

  Page<EnvironmentApiKey> findAllByEnvironmentId(UUID environmentId, Pageable pageable);

  /** Backs the per-environment cap and the legacy rotate endpoint's unambiguous-target check. */
  @Query(
      "SELECT COUNT(k) FROM EnvironmentApiKey k WHERE k.environment.id = :environmentId "
          + "AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
  long countActiveByEnvironmentId(
      @Param("environmentId") UUID environmentId, @Param("now") LocalDateTime now);

  /** Active keys of one environment, oldest first — the legacy rotate endpoint resolves its target here. */
  @Query(
      "SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment "
          + "WHERE k.environment.id = :environmentId AND k.revokedAt IS NULL "
          + "AND (k.expiresAt IS NULL OR k.expiresAt > :now) ORDER BY k.createdAt ASC")
  java.util.List<EnvironmentApiKey> findActiveByEnvironmentId(
      @Param("environmentId") UUID environmentId, @Param("now") LocalDateTime now);

  /**
   * The environment-level "last used" that {@code environments.last_used_at} used to hold, now
   * derived from its keys. Issue #56's deletion guard needs exactly this quantity.
   */
  @Query(
      "SELECT MAX(k.lastUsedAt) FROM EnvironmentApiKey k WHERE k.environment.id = :environmentId")
  Optional<LocalDateTime> findLastUsedAtByEnvironmentId(@Param("environmentId") UUID environmentId);

  /**
   * Stamps {@code last_used_at}. The {@code threshold} guard makes this a no-op when the timestamp
   * was updated recently, so it stays race-safe under concurrent SDK calls and lets the caller
   * throttle writes on the hot path. Must stay a bulk UPDATE — setting the field on a managed
   * entity would bump nothing here but would load the row on every SDK read.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE EnvironmentApiKey k SET k.lastUsedAt = :now "
          + "WHERE k.id = :id AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :threshold)")
  void touchLastUsedAt(
      @Param("id") UUID id,
      @Param("now") LocalDateTime now,
      @Param("threshold") LocalDateTime threshold);
}
```

- [ ] **Step 10: Run both tests**

Run: `./mvnw test -Dtest='EnvironmentApiKeyTest,EnvironmentApiKeyRepositoryTest'`
Expected: PASS.

- [ ] **Step 11: Run the full suite to prove nothing regressed**

Run: `./mvnw test`
Expected: PASS. The new table exists but nothing reads it, so every existing test is unaffected.

- [ ] **Step 12: Format and commit**

```bash
./mvnw spotless:apply
git add src/main/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKey.java \
        src/main/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepository.java \
        src/main/resources/db/changelog/migrations/019-create-environment-api-keys.xml \
        src/main/resources/db/changelog/db.changelog-master.xml \
        src/test/java/org/aibles/feature_flag/domain/entity/EnvironmentApiKeyTest.java \
        src/test/java/org/aibles/feature_flag/repository/EnvironmentApiKeyRepositoryTest.java
git commit -m "feat(security): add environment_api_key table and entity

Introduces the credential entity that will replace environments.api_key_hash,
with the expires_at/revoked_at pair that refresh_token already uses. Nothing
reads it yet — the cutover follows in the next change."
```

---

### Task 2: Cut authentication over to the new table

The atomic step. The backfill, the column drop, the filter rewrite and the three key-minting call sites cannot be separated: `ddl-auto=validate` fails the moment `Environment.apiKeyHash` maps a dropped column, and the filter cannot authenticate against a column that no longer exists. Splitting this leaves the build red between commits.

**Files:**
- Create: `src/main/resources/db/changelog/migrations/020-migrate-api-keys-to-key-table.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `src/main/java/org/aibles/feature_flag/domain/entity/Environment.java` (remove `apiKeyHash`, `lastUsedAt`)
- Modify: `src/main/java/org/aibles/feature_flag/repository/EnvironmentRepository.java` (remove `findByApiKeyHash`, `touchLastUsedAt`)
- Modify: `src/main/java/org/aibles/feature_flag/security/ApiKeyAuthenticationFilter.java`
- Modify: `src/main/java/org/aibles/feature_flag/security/ApiKeyAuthenticationToken.java`
- Modify: `src/main/java/org/aibles/feature_flag/controller/sdk/EvaluationController.java`
- Modify: `src/main/java/org/aibles/feature_flag/security/ratelimit/SdkRateLimitFilter.java`
- Modify: `src/main/java/org/aibles/feature_flag/config/SecurityConfig.java` (SDK chain wiring)
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImpl.java:53,147`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentTransferServiceImpl.java:66`
- Test: `src/test/java/org/aibles/feature_flag/security/ApiKeyAuthenticationFilterTest.java` (rewrite)
- Test: `src/test/java/org/aibles/feature_flag/migration/ApiKeyBackfillTest.java` (new)

**Interfaces:**
- Consumes: `EnvironmentApiKey`, `EnvironmentApiKeyRepository` from Task 1.
- Produces: SDK principal is `EnvironmentApiKey`. `ApiKeyAuthenticationFilter(EnvironmentApiKeyRepository, FeatureFlagMetrics, Clock)`.
- Produces: `EnvironmentApiKeyFactory.mint(Environment, String name, LocalDateTime expiresAt, UUID createdBy)` returning a `MintedKey(EnvironmentApiKey key, String plaintext)` record, used by the three call sites and by Task 4's service.

- [ ] **Step 1: Write the failing backfill test**

The backfill only has data to move when an environment predates it, which a fresh test database never has. A Liquibase context seeds one; the context is off by default so the seed never runs in dev or production.

Create `src/main/resources/db/changelog/migrations/020-migrate-api-keys-to-key-table.xml` with the seed changeset first — it is guarded by `context="backfill-test"` and must be ordered before the backfill:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <!--
      Moves every environment's key into environment_api_key and drops the old columns.
      Because the hash is COPIED rather than recomputed, every key already deployed in the
      field keeps authenticating across this migration. Getting that wrong logs out every
      SDK client in production, so 020-0 seeds a pre-migration row and ApiKeyBackfillTest
      asserts it survives. The seed runs only under the "backfill-test" context, which no
      dev or production run activates.
    -->

    <changeSet id="020-0-seed-pre-migration-environment" author="dev" context="backfill-test">
        <insert tableName="organizations">
            <column name="id" value="00000000-0000-0000-0000-0000000000a1"/>
            <column name="name" value="Backfill Fixture Org"/>
            <column name="slug" value="backfill-fixture-org"/>
        </insert>
        <insert tableName="projects">
            <column name="id" value="00000000-0000-0000-0000-0000000000b1"/>
            <column name="organization_id" value="00000000-0000-0000-0000-0000000000a1"/>
            <column name="name" value="Backfill Fixture Project"/>
        </insert>
        <insert tableName="environments">
            <column name="id" value="00000000-0000-0000-0000-0000000000c1"/>
            <column name="project_id" value="00000000-0000-0000-0000-0000000000b1"/>
            <column name="name" value="legacy-prod"/>
            <!-- SHA-256 of the literal string "legacy-plaintext-key", asserted in the test. -->
            <column name="api_key_hash"
                    value="99a6a2f8f0f1cf9b32a0ac0f30f1ba2d4b2a5d3e0d3f2c0a3f0a5f4d3e2c1b0a"/>
            <column name="type" value="PRODUCTION"/>
        </insert>
        <rollback>
            <delete tableName="environments">
                <where>id = '00000000-0000-0000-0000-0000000000c1'</where>
            </delete>
            <delete tableName="projects">
                <where>id = '00000000-0000-0000-0000-0000000000b1'</where>
            </delete>
            <delete tableName="organizations">
                <where>id = '00000000-0000-0000-0000-0000000000a1'</where>
            </delete>
        </rollback>
    </changeSet>

    <changeSet id="020-1-backfill-keys" author="dev">
        <!--
          id is left to its defaultValueComputed default, exactly as 016 does, so this statement
          contains no engine-specific UUID function and runs unchanged on PostgreSQL and H2.
          key_prefix is empty: the plaintext was never stored, so no prefix is recoverable.
        -->
        <sql>
            INSERT INTO environment_api_key
                (environment_id, name, key_hash, key_prefix, last_used_at)
            SELECT id, 'default', api_key_hash, '', last_used_at FROM environments
        </sql>
        <rollback>
            <sql>DELETE FROM environment_api_key WHERE name = 'default'</sql>
        </rollback>
    </changeSet>

    <changeSet id="020-2-drop-environment-key-columns" author="dev">
        <dropUniqueConstraint tableName="environments"
                              constraintName="uq_environments_api_key_hash"/>
        <dropColumn tableName="environments" columnName="api_key_hash"/>
        <dropColumn tableName="environments" columnName="last_used_at"/>
        <rollback>
            <addColumn tableName="environments">
                <column name="api_key_hash" type="VARCHAR(64)"/>
                <column name="last_used_at" type="TIMESTAMPTZ"/>
            </addColumn>
            <addUniqueConstraint tableName="environments"
                                 columnNames="api_key_hash"
                                 constraintName="uq_environments_api_key_hash"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

Register it in `db.changelog-master.xml` after the `019` line:

```xml
    <include file="db/changelog/migrations/020-migrate-api-keys-to-key-table.xml"/>
```

Then create `src/test/java/org/aibles/feature_flag/migration/ApiKeyBackfillTest.java`:

```java
package org.aibles.feature_flag.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The highest-consequence assertion in this feature: a key issued before migration 020 must keep
 * authenticating after it. The migration copies the stored hash rather than recomputing it, and
 * this test is what proves the copy landed where the authentication lookup now reads.
 *
 * <p>The pre-migration environment is seeded by changeset {@code 020-0}, activated only by the
 * {@code backfill-test} Liquibase context set below.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.liquibase.contexts=backfill-test")
class ApiKeyBackfillTest {

  private static final UUID SEEDED_ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final String SEEDED_PLAINTEXT = "legacy-plaintext-key";

  @Autowired private EnvironmentApiKeyRepository repository;

  @Test
  void aKeyIssuedBeforeTheMigrationStillResolves() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    assertThat(key.getEnvironment().getId()).isEqualTo(SEEDED_ENVIRONMENT_ID);
    assertThat(key.getName()).isEqualTo("default");
    assertThat(key.getRevokedAt()).isNull();
    assertThat(key.getExpiresAt()).isNull();
  }

  @Test
  void theBackfilledKeyIsActive() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    assertThat(repository.findActiveByKeyHash(key.getKeyHash(), java.time.LocalDateTime.now()))
        .isPresent();
  }

  @Test
  void theBackfilledKeyHasNoRecoverablePrefix() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    // The plaintext was never stored, so no prefix can be invented for a migrated row.
    assertThat(key.getKeyPrefix()).isEmpty();
  }
}
```

- [ ] **Step 2: Fix the seeded hash to the real value**

The hash literal in `020-0` above is a placeholder digit sequence and **will not match**. Compute the real one and replace it:

```bash
printf 'legacy-plaintext-key' | sha256sum
```

Paste the 64-char hex output into the `api_key_hash` column value in `020-0`. Run the test now:

Run: `./mvnw test -Dtest=ApiKeyBackfillTest`
Expected: FAIL — `Environment.apiKeyHash` still maps a column `020-2` dropped, so the context fails to start with a Hibernate schema validation error. That is the expected failure; the next steps remove the mapping.

- [ ] **Step 3: Strip the key columns off `Environment`**

In `src/main/java/org/aibles/feature_flag/domain/entity/Environment.java`, delete both fields and their javadoc:

```java
  /** SHA-256 hash (lowercase hex) of the SDK API key. The plaintext is never stored. */
  @Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
  private String apiKeyHash;

  /** Last time this key successfully authenticated an SDK request (audit). Coarse — see filter. */
  @Column(name = "last_used_at")
  private LocalDateTime lastUsedAt;
```

In `src/main/java/org/aibles/feature_flag/repository/EnvironmentRepository.java`, delete `findByApiKeyHash` and the whole `touchLastUsedAt` method with its javadoc, plus the now-unused `Modifying`, `Query`, `Param`, `Transactional` and `LocalDateTime` imports if nothing else uses them.

- [ ] **Step 4: Add the key factory**

Three call sites mint a key and all three need the same hash-plus-prefix construction. Create `src/main/java/org/aibles/feature_flag/util/EnvironmentApiKeyFactory.java`:

```java
package org.aibles.feature_flag.util;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;

/**
 * Builds a key row and its one-time plaintext together. Centralised because three call sites mint
 * keys — environment creation, environment cloning and the key API — and all three must derive the
 * stored prefix from the same plaintext they hand back, or the prefix stops identifying the key.
 */
public final class EnvironmentApiKeyFactory {

  /** The name given to the key an environment is born with. */
  public static final String DEFAULT_KEY_NAME = "default";

  private static final int PREFIX_LENGTH = 8;

  private EnvironmentApiKeyFactory() {}

  /** A key row paired with the plaintext that is returned to the caller exactly once. */
  public record MintedKey(EnvironmentApiKey key, String plaintext) {}

  public static MintedKey mint(
      Environment environment, String name, LocalDateTime expiresAt, UUID createdBy) {
    String plaintext = ApiKeyGenerator.generate();
    EnvironmentApiKey key =
        EnvironmentApiKey.builder()
            .environment(environment)
            .name(name)
            .keyHash(ApiKeyHasher.hash(plaintext))
            .keyPrefix(plaintext.substring(0, PREFIX_LENGTH))
            .expiresAt(expiresAt)
            .createdBy(createdBy)
            .build();
    return new MintedKey(key, plaintext);
  }
}
```

- [ ] **Step 5: Rewrite the SDK authentication filter**

Replace the body of `src/main/java/org/aibles/feature_flag/security/ApiKeyAuthenticationFilter.java`:

```java
package org.aibles.feature_flag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.logging.MdcKeys;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String API_KEY_HEADER = "X-Environment-Key";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Only re-stamp {@code last_used_at} once per window, to avoid a DB write per SDK call. */
  private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final FeatureFlagMetrics metrics;
  private final Clock clock;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String apiKey = request.getHeader(API_KEY_HEADER);

    if (!StringUtils.hasText(apiKey)) {
      metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_MISSING_KEY);
      writeUnauthorized(response, "Missing X-Environment-Key header");
      return;
    }

    Optional<EnvironmentApiKey> found = apiKeyRepository.findByKeyHash(ApiKeyHasher.hash(apiKey));
    if (found.isEmpty()) {
      rejectKey(response, "Invalid API key");
      return;
    }

    // Validity is evaluated here rather than in the query so the three failures stay
    // distinguishable. Telling a caller their key expired rather than "invalid" is the
    // difference between a five-minute fix and a support ticket, and it reveals nothing:
    // only someone already holding the key can see the distinction. An unknown hash gets
    // the flat "Invalid API key" above, so nothing leaks about keys the caller lacks.
    EnvironmentApiKey key = found.get();
    if (key.isRevoked()) {
      rejectKey(response, "API key has been revoked");
      return;
    }
    if (key.isExpired(clock)) {
      rejectKey(response, "API key has expired");
      return;
    }

    touchLastUsedAt(key);

    SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(key));

    // Tag logs for this request with the resolved environment id. Cleared centrally by
    // RequestCorrelationFilter's finally block, so no per-request cleanup is needed here.
    MDC.put(MdcKeys.ENV_ID, key.getEnvironment().getId().toString());

    filterChain.doFilter(request, response);
  }

  /** All key rejections share one counter — the metric must not become a revocation oracle. */
  private void rejectKey(HttpServletResponse response, String detail) throws IOException {
    metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_INVALID_KEY);
    writeUnauthorized(response, detail);
  }

  /**
   * Records SDK key usage, throttled to at most one write per {@link #LAST_USED_THROTTLE} window.
   * The in-memory check skips the DB round-trip for the common (recently-used) case; the
   * repository's threshold guard keeps the actual write race-safe.
   */
  private void touchLastUsedAt(EnvironmentApiKey key) {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime threshold = now.minus(LAST_USED_THROTTLE);
    if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(threshold)) {
      apiKeyRepository.touchLastUsedAt(key.getId(), now, threshold);
    }
  }

  private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthorized");
    problem.setDetail(detail);
    problem.setType(URI.create("about:blank"));

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    MAPPER.writeValue(response.getWriter(), problem);
  }
}
```

- [ ] **Step 6: Change the principal type**

In `ApiKeyAuthenticationToken.java`, replace every `Environment` with `EnvironmentApiKey` (field, constructor parameter, `getPrincipal()` return) and update the import. Add to the class javadoc:

```java
/**
 * SDK authentication token. The principal is the {@link EnvironmentApiKey} rather than the
 * environment, so downstream code can see which of an environment's keys made the call; the
 * environment is one hop away via {@link EnvironmentApiKey#getEnvironment()}.
 */
```

In `EvaluationController.java`, replace both principal casts:

```java
    Environment env = ((EnvironmentApiKey) authentication.getPrincipal()).getEnvironment();
```

and add `import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;`.

In `SdkRateLimitFilter.java`, change the pattern match and keep the bucket key on the environment:

```java
    if (auth != null && auth.getPrincipal() instanceof EnvironmentApiKey key) {
      // Keyed by environment, not by key: keying per key would let anyone who can mint keys
      // multiply the environment's effective quota, turning the limit into a formality.
      return key.getEnvironment().getId().toString();
    }
```

Update its import and the comment above it that names `Environment` as the principal.

- [ ] **Step 7: Rewire the SDK chain**

In `SecurityConfig.java`, replace the `environmentRepository` field with `EnvironmentApiKeyRepository apiKeyRepository`, add a `Clock clock` field (both injected via the existing constructor pattern), and update the chain:

```java
    ApiKeyAuthenticationFilter apiKeyFilter =
        new ApiKeyAuthenticationFilter(apiKeyRepository, metrics, clock);
```

Also update the comment on `addFilterAfter` that says "so the Environment principal is already resolved" to name `EnvironmentApiKey`.

- [ ] **Step 8: Move the three key-minting call sites**

`EnvironmentServiceImpl.create()` — replace the `plaintextKey` / `.apiKeyHash(...)` lines. Build and save the environment first, then mint its key:

```java
    Environment saved = environmentRepository.save(env);
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            saved,
            EnvironmentApiKeyFactory.DEFAULT_KEY_NAME,
            null,
            permissionService.currentUserId());
    apiKeyRepository.save(minted.key());
```

and return `toSecretResponse(saved, minted.plaintext())`. Inject `EnvironmentApiKeyRepository apiKeyRepository`.

`EnvironmentServiceImpl.rotateApiKey()` — Task 5 rewrites this properly. For now keep it compiling and behaviour-identical by revoking every active key and minting one:

```java
    LocalDateTime now = LocalDateTime.now(clock);
    apiKeyRepository
        .findActiveByEnvironmentId(id, now)
        .forEach(existing -> existing.setRevokedAt(now));
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env,
            EnvironmentApiKeyFactory.DEFAULT_KEY_NAME,
            null,
            permissionService.currentUserId());
    apiKeyRepository.save(minted.key());
```

Inject `Clock clock` into `EnvironmentServiceImpl`.

`EnvironmentTransferServiceImpl.clone()` — the same treatment. A clone deliberately mints its own key rather than copying the source's; that invariant must survive:

```java
    Environment savedClone = environmentRepository.save(clone);
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            savedClone,
            EnvironmentApiKeyFactory.DEFAULT_KEY_NAME,
            null,
            permissionService.currentUserId());
    apiKeyRepository.save(minted.key());
```

Read the surrounding method before editing — the existing builder sets `.apiKeyHash(...)` inline, so the save has to be reordered as above.

- [ ] **Step 9: Rewrite the filter test**

Rewrite `ApiKeyAuthenticationFilterTest.java`. Keep the existing missing-key and unknown-key tests, changing the mock to `EnvironmentApiKeyRepository`, and add the lifecycle cases:

```java
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

  @Mock private EnvironmentApiKeyRepository apiKeyRepository;
  @Mock private FilterChain filterChain;

  private ApiKeyAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    filter =
        new ApiKeyAuthenticationFilter(
            apiKeyRepository, new FeatureFlagMetrics(new SimpleMeterRegistry()), clock);
  }

  private EnvironmentApiKey key(LocalDateTime expiresAt, LocalDateTime revokedAt) {
    Environment env = Environment.builder().name("prod").build();
    env.setId(UUID.randomUUID());
    return EnvironmentApiKey.builder()
        .id(UUID.randomUUID())
        .environment(env)
        .name("ios")
        .keyHash(ApiKeyHasher.hash("plaintext"))
        .expiresAt(expiresAt)
        .revokedAt(revokedAt)
        .build();
  }

  @Test
  void authenticatesWithAValidKeyAndSetsTheKeyAsPrincipal() throws Exception {
    EnvironmentApiKey valid = key(null, null);
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(valid));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isSameAs(valid);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void rejectsARevokedKeyWithAMessageNamingRevocation() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(null, NOW.minusDays(1))));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("API key has been revoked");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(filterChain);
  }

  @Test
  void rejectsAnExpiredKeyWithAMessageNamingExpiry() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(NOW.minusMinutes(1), null)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("API key has expired");
    verifyNoInteractions(filterChain);
  }

  @Test
  void rejectsAKeyAtTheExactExpiryInstant() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(NOW, null)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    verifyNoInteractions(filterChain);
  }

  @Test
  void unknownHashSaysOnlyInvalidAndNeverMentionsRevocationOrExpiry() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    // Nothing may leak about a key the caller does not hold.
    assertThat(response.getContentAsString()).contains("Invalid API key");
    assertThat(response.getContentAsString()).doesNotContain("revoked").doesNotContain("expired");
  }

  @Test
  void skipsTheUsageWriteWhenTheStampIsRecent() throws Exception {
    EnvironmentApiKey recent = key(null, null);
    recent.setLastUsedAt(NOW.minusMinutes(1));
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(recent));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(apiKeyRepository, never()).touchLastUsedAt(any(), any(), any());
  }

  @Test
  void writesTheUsageStampWhenItIsStale() throws Exception {
    EnvironmentApiKey stale = key(null, null);
    stale.setLastUsedAt(NOW.minusHours(1));
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(stale));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(apiKeyRepository).touchLastUsedAt(eq(stale.getId()), eq(NOW), any());
  }
```

- [ ] **Step 10: Fix the remaining compilation failures in existing tests**

Run: `./mvnw test-compile`

Every failure is a test constructing `Environment.builder().apiKeyHash(...)` or casting the principal to `Environment`. Fix each by minting an `EnvironmentApiKey` for that environment instead. The known set is `EnvironmentServiceImplTest`, `EnvironmentTransferServiceImplTest`, `EvaluationControllerTest`, `SecurityChainIntegrationTest`, `RateLimitIntegrationTest`. Work through the compiler output — do not guess the list.

- [ ] **Step 11: Run the full suite**

Run: `./mvnw test`
Expected: PASS, including `ApiKeyBackfillTest`.

- [ ] **Step 12: Verify the backfill against real data in local PostgreSQL**

The automated test proves the SQL on H2 with one seeded row. Confirm it on the real engine with real rows before trusting it. PostgreSQL runs natively on this machine (not in Docker — `docker compose up` will collide on port 5432):

```bash
psql -U ff_user -d feature_flag_db -c \
  "SELECT count(*) AS environments FROM environments;"
./mvnw spring-boot:run   # applies 019 + 020, then Ctrl-C once started
psql -U ff_user -d feature_flag_db -c \
  "SELECT count(*) AS keys, count(DISTINCT environment_id) AS envs FROM environment_api_key;"
```

Expected: `keys` and `envs` both equal the pre-migration environment count. If they differ, stop — the backfill dropped or duplicated a credential.

- [ ] **Step 13: Format and commit**

```bash
./mvnw spotless:apply
git add -A
git commit -m "feat(security)!: authenticate SDK calls against environment_api_key

Moves the SDK credential out of environments.api_key_hash into its own
table and makes EnvironmentApiKey the SDK principal, so a request can be
attributed to one of an environment's several keys. Revoked and expired
keys are now rejected with distinct 401 messages that share one metric
counter.

The migration copies each stored hash rather than recomputing it, so keys
already deployed in the field keep authenticating; ApiKeyBackfillTest
seeds a pre-migration environment under a Liquibase context and asserts
exactly that."
```

---

### Task 3: ABAC actions for key management

Pure addition to the permission model — no call site uses these yet, so the change is safe in isolation and the tests pin the rules before anything depends on them.

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/domain/enums/Action.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/PermissionService.java`
- Test: `src/test/java/org/aibles/feature_flag/service/impl/PermissionServiceTest.java`

**Interfaces:**
- Produces: `Action.ENV_KEY_CREATE`, `ENV_KEY_CREATE_PRODUCTION`, `ENV_KEY_REVOKE`, `ENV_KEY_REVOKE_PRODUCTION`.
- Produces: `PermissionService.WINDOW_EXEMPT` behaviour — an elevated action in that set skips the change-window check but keeps the elevated-permission check.

- [ ] **Step 1: Write the failing permission tests**

Add to `PermissionServiceTest`. Read the existing production-rule tests in that file first and reuse their fixture builders rather than inventing new ones.

```java
  @Test
  void adminCannotCreateAKeyOnAProductionEnvironment() {
    // Issuing a new production credential changes what production SDKs can do, so it is
    // elevated for the same reason archiving a flag is.
    givenOrgRole(MemberRole.ADMIN);
    Environment prod = productionEnvironment(null, null);

    assertThatThrownBy(
            () ->
                permissionService.check(
                    Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, prod)))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("requires elevated permission");
  }

  @Test
  void ownerCanCreateAProductionKeyInsideTheChangeWindow() {
    givenOrgRole(MemberRole.OWNER);
    givenClockHour(10);
    Environment prod = productionEnvironment(9, 17);

    assertThatCode(
            () ->
                permissionService.check(
                    Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, prod)))
        .doesNotThrowAnyException();
  }

  @Test
  void ownerCannotCreateAProductionKeyOutsideTheChangeWindow() {
    givenOrgRole(MemberRole.OWNER);
    givenClockHour(20);
    Environment prod = productionEnvironment(9, 17);

    assertThatThrownBy(
            () ->
                permissionService.check(
                    Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, prod)))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("change window");
  }

  @Test
  void adminCannotRevokeAProductionKey() {
    // Revoking is an off-switch for production SDKs, so it stays OWNER-gated.
    givenOrgRole(MemberRole.ADMIN);
    Environment prod = productionEnvironment(null, null);

    assertThatThrownBy(
            () ->
                permissionService.check(
                    Action.ENV_KEY_REVOKE, ResourceRef.environment(PROJECT_ID, prod)))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void ownerCanRevokeAProductionKeyOutsideTheChangeWindow() {
    // THE test that pins the rule D exception. A key leaked at 03:00 must be withdrawable
    // at 03:00: revocation only ever reduces access, so the window prevents no attack while
    // the delay it imposes is exactly what an attacker holding a leaked key wants.
    givenOrgRole(MemberRole.OWNER);
    givenClockHour(3);
    Environment prod = productionEnvironment(9, 17);

    assertThatCode(
            () ->
                permissionService.check(
                    Action.ENV_KEY_REVOKE, ResourceRef.environment(PROJECT_ID, prod)))
        .doesNotThrowAnyException();
  }

  @Test
  void theWindowExemptionDoesNotLeakToKeyCreation() {
    // Guards against someone later adding ENV_KEY_CREATE_PRODUCTION to WINDOW_EXEMPT.
    givenOrgRole(MemberRole.OWNER);
    givenClockHour(3);
    Environment prod = productionEnvironment(9, 17);

    assertThatThrownBy(
            () ->
                permissionService.check(
                    Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, prod)))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("change window");
  }

  @Test
  void keyActionsOnANonProductionEnvironmentNeedOnlyAdmin() {
    givenOrgRole(MemberRole.ADMIN);
    Environment dev = developmentEnvironment();

    assertThatCode(
            () -> {
              permissionService.check(
                  Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, dev));
              permissionService.check(
                  Action.ENV_KEY_REVOKE, ResourceRef.environment(PROJECT_ID, dev));
            })
        .doesNotThrowAnyException();
  }

  @Test
  void viewerCanDoNeither() {
    givenOrgRole(MemberRole.VIEWER);
    Environment dev = developmentEnvironment();

    assertThatThrownBy(
            () ->
                permissionService.check(
                    Action.ENV_KEY_CREATE, ResourceRef.environment(PROJECT_ID, dev)))
        .isInstanceOf(UnauthorizedException.class);
  }
```

If `givenOrgRole`, `givenClockHour`, `productionEnvironment` or `developmentEnvironment` do not already exist in the test class under those names, use whatever equivalent helpers it does have — do not add duplicates.

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=PermissionServiceTest`
Expected: FAIL — `Action.ENV_KEY_CREATE` does not exist.

- [ ] **Step 3: Add the actions**

In `Action.java`, after the existing `ENV_*` block:

```java
  ENV_KEY_CREATE,
  ENV_KEY_CREATE_PRODUCTION,
  ENV_KEY_REVOKE,
  ENV_KEY_REVOKE_PRODUCTION,
```

- [ ] **Step 4: Wire them into the permission model**

In `PermissionService.buildRoleActions()`, add `Action.ENV_KEY_CREATE` and `Action.ENV_KEY_REVOKE` to the `admin` set, and `Action.ENV_KEY_CREATE_PRODUCTION` and `Action.ENV_KEY_REVOKE_PRODUCTION` to the `owner` set.

`PRODUCTION_ELEVATED` currently uses `Map.of(...)`, which caps at 10 pairs — six entries is fine, but switch to `Map.ofEntries(Map.entry(...))` now so the next addition does not have to:

```java
  private static final Map<Action, Action> PRODUCTION_ELEVATED =
      Map.ofEntries(
          Map.entry(Action.FLAG_STATE_UPDATE, Action.FLAG_STATE_UPDATE_PRODUCTION),
          Map.entry(Action.FLAG_ARCHIVE, Action.FLAG_ARCHIVE_PRODUCTION),
          Map.entry(Action.ENV_ROTATE_KEY, Action.ENV_ROTATE_KEY_PRODUCTION),
          Map.entry(Action.ENV_DELETE, Action.ENV_DELETE_PRODUCTION),
          Map.entry(Action.ENV_KEY_CREATE, Action.ENV_KEY_CREATE_PRODUCTION),
          Map.entry(Action.ENV_KEY_REVOKE, Action.ENV_KEY_REVOKE_PRODUCTION));
```

Add the exemption set below it:

```java
  /**
   * Elevated actions that skip the change window (rule D) while still requiring the elevated
   * permission (rule B).
   *
   * <p>This is the <strong>only</strong> exception to rule D and it must stay that way unless the
   * same argument holds: revocation is monotonically restrictive — it can only remove access,
   * never grant it — so the window prevents no attack, while the delay it imposes is precisely
   * the window an attacker holding a leaked key wants. A key leaked at 03:00 has to be
   * withdrawable at 03:00, and widening the window is itself OWNER-gated, so a strict reading
   * would leave no break-glass path at all. Rotation is deliberately NOT here: it issues a new
   * production credential, which is a planned change and stays fully windowed.
   */
  private static final Set<Action> WINDOW_EXEMPT = Set.of(Action.ENV_KEY_REVOKE_PRODUCTION);
```

In `check(...)`, guard the window branch with it:

```java
    if (required != action
        && !WINDOW_EXEMPT.contains(required)
        && productionEnvs.stream().anyMatch(e -> !withinChangeWindow(e))) {
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -Dtest=PermissionServiceTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
./mvnw spotless:apply
git add -A
git commit -m "feat(security): ABAC actions for API key creation and revocation

Both reach production behaviour and are elevated accordingly. Revocation
carries the first deliberate exception to the change-window rule: it only
ever reduces access, so the window prevents no attack, while the delay it
imposes is exactly what an attacker holding a leaked key wants."
```

---

### Task 4: Key management API — create, list, revoke

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/dto/request/CreateApiKeyRequest.java`
- Create: `src/main/java/org/aibles/feature_flag/dto/response/ApiKeyResponse.java`
- Create: `src/main/java/org/aibles/feature_flag/dto/response/ApiKeySecretResponse.java`
- Create: `src/main/java/org/aibles/feature_flag/service/EnvironmentApiKeyService.java`
- Create: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java`
- Create: `src/main/java/org/aibles/feature_flag/controller/admin/EnvironmentApiKeyController.java`
- Modify: `src/main/java/org/aibles/feature_flag/domain/enums/AuditAction.java`
- Test: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`

**Interfaces:**
- Consumes: `EnvironmentApiKeyRepository`, `EnvironmentApiKeyFactory.mint(...)`, `Action.ENV_KEY_CREATE`, `Action.ENV_KEY_REVOKE`.
- Produces: `EnvironmentApiKeyService.create(UUID envId, CreateApiKeyRequest)` → `ApiKeySecretResponse`; `.list(UUID envId, Pageable)` → `Page<ApiKeyResponse>`; `.revoke(UUID envId, UUID keyId)` → `void`.
- Produces: `EnvironmentApiKeyServiceImpl.MAX_ACTIVE_KEYS_PER_ENVIRONMENT = 10`.

- [ ] **Step 1: Write the DTOs**

`CreateApiKeyRequest.java`:

```java
package org.aibles.feature_flag.dto.request;

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

  /** Optional planned retirement. Absent means the key never expires. */
  @Future private LocalDateTime expiresAt;
}
```

`ApiKeyResponse.java`:

```java
package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/** The secret-free view of a key. Never carries the plaintext or the hash. */
@Data
@Builder
public class ApiKeyResponse {
  private UUID id;
  private UUID environmentId;
  private String name;
  /** First 8 characters of the plaintext — how an operator identifies the key. Empty for keys migrated from the single-key era. */
  private String keyPrefix;
  private LocalDateTime expiresAt;
  private LocalDateTime revokedAt;
  private LocalDateTime lastUsedAt;
  private UUID createdBy;
  private LocalDateTime createdAt;
  /** Derived: not revoked and not past its expiry. */
  private boolean active;
}
```

`ApiKeySecretResponse.java`:

```java
package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Returned <strong>only</strong> on key creation and rotation. Carries the plaintext exactly once —
 * it is never stored (only its SHA-256 hash is) and cannot be retrieved again, so the caller must
 * capture it now. Every read endpoint returns the secret-free {@link ApiKeyResponse}.
 */
@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class ApiKeySecretResponse {
  private ApiKeyResponse key;
  private String apiKey;
}
```

- [ ] **Step 2: Write the failing service test**

Create `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`. Mock `EnvironmentApiKeyRepository`, `EnvironmentRepository`, `PermissionService`, `AuditService`, and use a fixed `Clock`.

```java
  @Test
  void createReturnsThePlaintextOnceAndStoresOnlyItsHash() {
    ApiKeySecretResponse response = service.create(ENV_ID, request("ios", null));

    assertThat(response.getApiKey()).hasSize(64);
    EnvironmentApiKey saved = captureSavedKey();
    assertThat(saved.getKeyHash()).isEqualTo(ApiKeyHasher.hash(response.getApiKey()));
    assertThat(saved.getKeyHash()).isNotEqualTo(response.getApiKey());
    assertThat(saved.getKeyPrefix()).isEqualTo(response.getApiKey().substring(0, 8));
  }

  @Test
  void createChecksEnvKeyCreateAgainstTheTargetEnvironment() {
    service.create(ENV_ID, request("ios", null));

    // The environment must be attached to the ResourceRef, or the production rules never fire.
    verify(permissionService)
        .check(eq(Action.ENV_KEY_CREATE), argThat(ref -> ref.environment() == environment));
  }

  @Test
  void createAuditsTheEventWithoutTheKeyMaterial() {
    service.create(ENV_ID, request("ios", null));

    verify(auditService)
        .record(
            eq(AuditEntityType.API_KEY),
            any(),
            eq(AuditAction.CREATE_API_KEY),
            eq(ORG_ID),
            isNull(),
            isNull());
  }

  @Test
  void createRejectsAnEleventhActiveKey() {
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(10L);

    assertThatThrownBy(() -> service.create(ENV_ID, request("ios", null)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("maximum of 10 active API keys");
  }

  @Test
  void theCapCountsOnlyActiveKeysSoRevokingFreesASlot() {
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(9L);

    assertThatCode(() -> service.create(ENV_ID, request("ios", null))).doesNotThrowAnyException();
  }

  @Test
  void listNeverExposesThePlaintextOrTheHash() {
    when(apiKeyRepository.findAllByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(new PageImpl<>(List.of(activeKey())));

    Page<ApiKeyResponse> page = service.list(ENV_ID, PageRequest.of(0, 20));

    assertThat(page.getContent()).singleElement().satisfies(r -> {
      assertThat(r.getKeyPrefix()).isEqualTo("abcd1234");
      assertThat(r).hasNoNullFieldsOrPropertiesExcept("expiresAt", "revokedAt", "lastUsedAt");
    });
    assertThat(page.getContent().toString()).doesNotContain(ACTIVE_KEY_HASH);
  }

  @Test
  void listMarksAnExpiredKeyInactive() {
    when(apiKeyRepository.findAllByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(new PageImpl<>(List.of(keyExpiringAt(NOW.minusDays(1)))));

    assertThat(service.list(ENV_ID, PageRequest.of(0, 20)).getContent())
        .singleElement()
        .extracting(ApiKeyResponse::isActive)
        .isEqualTo(false);
  }

  @Test
  void revokeStampsRevokedAtFromTheClock() {
    EnvironmentApiKey key = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

    service.revoke(ENV_ID, KEY_ID);

    assertThat(key.getRevokedAt()).isEqualTo(NOW);
  }

  @Test
  void revokeIsRejectedWhenTheKeyIsAlreadyRevoked() {
    EnvironmentApiKey key = activeKey();
    key.setRevokedAt(NOW.minusDays(1));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

    assertThatThrownBy(() -> service.revoke(ENV_ID, KEY_ID))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("already revoked");
  }

  @Test
  void revokeRejectsAKeyBelongingToAnotherEnvironment() {
    EnvironmentApiKey foreign = activeKey();
    foreign.setEnvironment(otherEnvironment());
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(foreign));

    // 404, not 403: a guessed key id must not confirm that the key exists elsewhere.
    assertThatThrownBy(() -> service.revoke(ENV_ID, KEY_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void revokeChecksEnvKeyRevokeAgainstTheTargetEnvironment() {
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.revoke(ENV_ID, KEY_ID);

    verify(permissionService)
        .check(eq(Action.ENV_KEY_REVOKE), argThat(ref -> ref.environment() == environment));
  }
```

- [ ] **Step 3: Run and confirm failure**

Run: `./mvnw test -Dtest=EnvironmentApiKeyServiceImplTest`
Expected: FAIL — the service does not exist.

- [ ] **Step 4: Add the audit actions**

In `AuditAction.java`, after `ROTATE_API_KEY`:

```java
  /** A new SDK key was minted for an environment (never records the key itself). */
  CREATE_API_KEY,
  /** An SDK key was withdrawn. Soft: the row survives so the audit trail keeps its referent. */
  REVOKE_API_KEY,
```

- [ ] **Step 5: Write the service interface and implementation**

`EnvironmentApiKeyService.java` declares `create`, `list`, `revoke` with the signatures in the Interfaces block above.

`EnvironmentApiKeyServiceImpl.java` — the shape each method follows:

```java
  public static final int MAX_ACTIVE_KEYS_PER_ENVIRONMENT = 10;

  @Override
  @Transactional
  public ApiKeySecretResponse create(UUID environmentId, CreateApiKeyRequest request) {
    Environment env = findEnvironment(environmentId);
    // The environment must ride on the ResourceRef: without it the production rules cannot
    // see that this is a production credential and rule B never fires.
    permissionService.check(
        Action.ENV_KEY_CREATE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

    LocalDateTime now = LocalDateTime.now(clock);
    if (apiKeyRepository.countActiveByEnvironmentId(environmentId, now)
        >= MAX_ACTIVE_KEYS_PER_ENVIRONMENT) {
      throw new DuplicateResourceException(
          "Environment has reached the maximum of "
              + MAX_ACTIVE_KEYS_PER_ENVIRONMENT
              + " active API keys");
    }

    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, request.getName(), request.getExpiresAt(), permissionService.currentUserId());
    EnvironmentApiKey saved = apiKeyRepository.save(minted.key());

    // before/after stay null: the ledger records that a key event happened, never the key.
    auditService.record(
        AuditEntityType.API_KEY,
        saved.getId(),
        AuditAction.CREATE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);

    return ApiKeySecretResponse.builder()
        .key(toResponse(saved))
        .apiKey(minted.plaintext())
        .build();
  }

  @Override
  @Transactional
  public void revoke(UUID environmentId, UUID keyId) {
    EnvironmentApiKey key = findKeyIn(environmentId, keyId);
    Environment env = key.getEnvironment();
    permissionService.check(
        Action.ENV_KEY_REVOKE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

    if (key.isRevoked()) {
      throw new DuplicateResourceException("API key is already revoked");
    }
    key.setRevokedAt(LocalDateTime.now(clock));
    apiKeyRepository.save(key);

    auditService.record(
        AuditEntityType.API_KEY,
        keyId,
        AuditAction.REVOKE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);
  }

  /**
   * Resolves a key and asserts it belongs to the named environment. A key from another
   * environment is reported as not found, not as forbidden: a 403 would confirm to someone
   * guessing ids that the key exists somewhere.
   */
  private EnvironmentApiKey findKeyIn(UUID environmentId, UUID keyId) {
    EnvironmentApiKey key =
        apiKeyRepository
            .findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));
    if (!key.getEnvironment().getId().equals(environmentId)) {
      throw new ResourceNotFoundException("ApiKey", keyId);
    }
    return key;
  }
```

`list()` checks `Action.ENV_READ` on the project, maps through `toResponse`, and sets `active` from `key.isActive(clock)`.

- [ ] **Step 6: Write the controller**

`EnvironmentApiKeyController.java`, following `EnvironmentController`'s annotation style exactly:

```java
@RestController
@RequestMapping("/api/v1/environments/{envId}/api-keys")
@RequiredArgsConstructor
public class EnvironmentApiKeyController {

  private final EnvironmentApiKeyService apiKeyService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiKeySecretResponse create(
      @PathVariable UUID envId, @Valid @RequestBody CreateApiKeyRequest request) {
    return apiKeyService.create(envId, request);
  }

  @GetMapping
  public PageResponse<ApiKeyResponse> list(
      @PathVariable UUID envId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(apiKeyService.list(envId, pageable));
  }

  @DeleteMapping("/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID envId, @PathVariable UUID keyId) {
    apiKeyService.revoke(envId, keyId);
  }
}
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw test -Dtest=EnvironmentApiKeyServiceImplTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite, format, commit**

```bash
./mvnw test
./mvnw spotless:apply
git add -A
git commit -m "feat(api): create, list and revoke environment API keys

Revocation is soft — the row survives so audit entries keep their
referent, and DELETE is the verb callers expect. A key from another
environment reports 404 rather than 403 so a guessed id cannot confirm
the key exists elsewhere."
```

---

### Task 5: Rotation with a grace period

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/service/EnvironmentApiKeyService.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImpl.java`
- Modify: `src/main/java/org/aibles/feature_flag/controller/admin/EnvironmentApiKeyController.java`
- Create: `src/main/java/org/aibles/feature_flag/dto/request/RotateApiKeyRequest.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImpl.java` (`rotateApiKey`)
- Test: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentApiKeyServiceImplTest.java`
- Test: `src/test/java/org/aibles/feature_flag/service/impl/EnvironmentServiceImplTest.java`

**Interfaces:**
- Produces: `EnvironmentApiKeyService.rotate(UUID envId, UUID keyId, RotateApiKeyRequest)` → `ApiKeySecretResponse`.

- [ ] **Step 1: Write `RotateApiKeyRequest`**

```java
package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RotateApiKeyRequest {

  /**
   * How long the old key keeps working after the new one is issued. {@code 0} revokes it
   * immediately, reproducing the pre-multi-key hard cutover. Anything above that is the point of
   * the feature: both keys authenticate while the SDK fleet is redeployed.
   */
  @Min(0)
  @Max(720)
  private int graceHours = 0;
}
```

- [ ] **Step 2: Write the failing rotation tests**

```java
  @Test
  void rotateWithGraceLeavesTheOldKeyValidUntilTheDeadline() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    assertThat(old.getRevokedAt()).isNull();
    assertThat(old.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    assertThat(old.isActive(fixedClock)).isTrue();
  }

  @Test
  void rotateWithZeroGraceRevokesTheOldKeyImmediately() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(0));

    assertThat(old.getRevokedAt()).isEqualTo(NOW);
    assertThat(old.isActive(fixedClock)).isFalse();
  }

  @Test
  void theRotatedKeyCarriesTheOldNameForward() {
    EnvironmentApiKey old = activeKey();
    old.setName("ios-app");
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    // Names are labels, not identifiers — a collision during the grace window is expected
    // and the two rows are told apart by prefix and creation time.
    assertThat(captureSavedKey().getName()).isEqualTo("ios-app");
  }

  @Test
  void rotateIssuesAFreshSecretNotTheOldOne() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    ApiKeySecretResponse response = service.rotate(ENV_ID, KEY_ID, grace(24));

    assertThat(ApiKeyHasher.hash(response.getApiKey())).isNotEqualTo(old.getKeyHash());
  }

  @Test
  void rotateAuthorizesAsEnvRotateKeyNotAsCreatePlusRevoke() {
    // Decomposing rotation into ENV_KEY_CREATE + ENV_KEY_REVOKE would drag it into the
    // revoke window exemption, which rotation must not have: issuing a new production
    // credential is a planned change and stays fully windowed.
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    verify(permissionService)
        .check(eq(Action.ENV_ROTATE_KEY), argThat(ref -> ref.environment() == environment));
    verify(permissionService, never()).check(eq(Action.ENV_KEY_REVOKE), any());
  }

  @Test
  void rotatingAnAlreadyRevokedKeyIsRejected() {
    EnvironmentApiKey old = activeKey();
    old.setRevokedAt(NOW.minusDays(1));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(24)))
        .isInstanceOf(DuplicateResourceException.class);
  }
```

And in `EnvironmentServiceImplTest`, for the legacy endpoint:

```java
  @Test
  void legacyRotateRotatesTheSingleActiveKey() {
    when(apiKeyRepository.findActiveByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(List.of(theOnlyKey));

    EnvironmentSecretResponse response = service.rotateApiKey(ENV_ID);

    assertThat(theOnlyKey.getRevokedAt()).isEqualTo(NOW);
    assertThat(response.getApiKey()).isNotNull();
  }

  @Test
  void legacyRotateRefusesWhenTheEnvironmentHasSeveralActiveKeys() {
    when(apiKeyRepository.findActiveByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(List.of(keyA, keyB));

    assertThatThrownBy(() -> service.rotateApiKey(ENV_ID))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("/api-keys/{keyId}/rotate");
  }

  @Test
  void legacyRotateRefusesWhenTheEnvironmentHasNoActiveKey() {
    when(apiKeyRepository.findActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(List.of());

    assertThatThrownBy(() -> service.rotateApiKey(ENV_ID))
        .isInstanceOf(DuplicateResourceException.class);
  }
```

`DuplicateResourceException` is the 409 in this codebase (`GlobalExceptionHandler:33`); `InvalidRequestException` is the 400 (`:58`). The spec calls for 409 here — an ambiguous target is a state conflict, not a malformed request — so it is the former.

- [ ] **Step 3: Run and confirm failure**

Run: `./mvnw test -Dtest='EnvironmentApiKeyServiceImplTest,EnvironmentServiceImplTest'`
Expected: FAIL — `rotate` does not exist.

- [ ] **Step 4: Implement `rotate`**

```java
  @Override
  @Transactional
  public ApiKeySecretResponse rotate(UUID environmentId, UUID keyId, RotateApiKeyRequest request) {
    EnvironmentApiKey old = findKeyIn(environmentId, keyId);
    Environment env = old.getEnvironment();
    // ENV_ROTATE_KEY, not CREATE+REVOKE: one door, and rotation stays fully windowed.
    permissionService.check(
        Action.ENV_ROTATE_KEY,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

    if (old.isRevoked()) {
      throw new DuplicateResourceException("API key is already revoked");
    }

    LocalDateTime now = LocalDateTime.now(clock);
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, old.getName(), old.getExpiresAt(), permissionService.currentUserId());
    EnvironmentApiKey fresh = apiKeyRepository.save(minted.key());

    if (request.getGraceHours() == 0) {
      old.setRevokedAt(now);
    } else {
      old.setExpiresAt(now.plusHours(request.getGraceHours()));
    }
    apiKeyRepository.save(old);

    eventPublisher.publishEvent(
        new ApiKeyRotatedEvent(
            env.getId(),
            env.getName(),
            env.getProject().getName(),
            permissionService.currentUserEmail()));
    auditService.record(
        AuditEntityType.API_KEY,
        fresh.getId(),
        AuditAction.ROTATE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);

    return ApiKeySecretResponse.builder().key(toResponse(fresh)).apiKey(minted.plaintext()).build();
  }
```

Note the new key inherits `old.getExpiresAt()` — a key issued with a fixed lifetime rotates into another with the same deadline rather than silently becoming permanent.

- [ ] **Step 5: Add the controller endpoint**

```java
  @PostMapping("/{keyId}/rotate")
  public ApiKeySecretResponse rotate(
      @PathVariable UUID envId,
      @PathVariable UUID keyId,
      @Valid @RequestBody(required = false) RotateApiKeyRequest request) {
    return apiKeyService.rotate(
        envId, keyId, request != null ? request : new RotateApiKeyRequest());
  }
```

- [ ] **Step 6: Rewrite the legacy env-level rotate**

Replace the placeholder from Task 2 in `EnvironmentServiceImpl.rotateApiKey()`:

```java
  @Override
  @Transactional
  public EnvironmentSecretResponse rotateApiKey(UUID id) {
    Environment env = findById(id);
    List<EnvironmentApiKey> active =
        apiKeyRepository.findActiveByEnvironmentId(id, LocalDateTime.now(clock));
    // "The" key is only meaningful while there is exactly one. With several, the caller has
    // to say which — silently picking one would revoke a credential they did not name.
    if (active.size() != 1) {
      throw new DuplicateResourceException(
          "Environment has "
              + active.size()
              + " active API keys; use POST /api/v1/environments/{envId}/api-keys/{keyId}/rotate");
    }
    ApiKeySecretResponse rotated =
        apiKeyService.rotate(id, active.get(0).getId(), new RotateApiKeyRequest());
    return toSecretResponse(env, rotated.getApiKey());
  }
```

Delete the now-duplicated event publication and audit call from this method — `apiKeyService.rotate` already does both.

- [ ] **Step 7: Run tests, format, commit**

```bash
./mvnw test
./mvnw spotless:apply
git add -A
git commit -m "feat(api): rotate an API key with a grace period

Rotation mints the replacement and gives the old key a deadline instead
of killing it, so an SDK fleet can be redeployed before the old
credential stops working. graceHours=0 keeps the old hard-cutover
behaviour, which is what the environment-level endpoint still uses; that
endpoint now refuses with 409 when 'the' key is ambiguous."
```

---

### Task 6: Documentation

**Files:**
- Modify: `CLAUDE.md`, `docs/ABAC.md`, `docs/adr/ADR-0006-abac-authorization-model.md`, `docs/architecture.md`, `docs/main-flows.md`, `docs/postman/Feature_Flag_Platform.postman_collection.json`, `docs/demo/api-demo-flow.http`

- [ ] **Step 1: Update `CLAUDE.md`**

Rewrite the "API Key generation" section: keys live in `environment_api_key`, an environment may hold several, `ApiKeyGenerator` is unchanged but the row also stores an 8-char prefix, and `EnvironmentApiKeyFactory.mint` is the single construction point used by environment creation, cloning and the key API. Update the migration range to `001–020`. In the permission-model section, add the four new actions and — prominently — the `WINDOW_EXEMPT` exception, since that section currently states rule D without exceptions.

- [ ] **Step 2: Update `docs/ABAC.md` and ADR-0006**

Add the exception with its reasoning and name `PermissionServiceTest.ownerCanRevokeAProductionKeyOutsideTheChangeWindow` as the test that pins it.

- [ ] **Step 3: Update `docs/main-flows.md` flow 3**

Flow 3 is written entirely around one key per environment ("một key = một môi trường"). Rewrite its "Cơ chế" and request sections for the new endpoints, and add error cases for revoked and expired keys. Also update the endpoint quick-reference table at the end of the file with the four new routes and their actions, and mark rotation's production behaviour.

- [ ] **Step 4: Update Postman and the demo flow**

Add requests for create / list / revoke / rotate. In `api-demo-flow.http`, add a step that creates a second key and shows both authenticating, then revokes one and shows the other still working — that sequence is the whole point of the feature.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: multiple API keys per environment with expiry and revocation"
```

---

### Task 7: Security review and PR

- [ ] **Step 1: Run the security review**

The change touches `security/` and `db/changelog/migrations/`, so `CLAUDE.md` requires it. Run the `security-review` skill over the branch diff and address every CRITICAL and HIGH finding.

- [ ] **Step 2: Full verification**

Run: `./mvnw verify`
Expected: PASS, including `spotless:check` and the JaCoCo coverage gate.

- [ ] **Step 3: Save memory before pushing**

The pre-push hook (`.claude/hooks/pre-push-memory-gate.sh`) blocks a push whose commits touch code but not `.claude/memory/`. Run `/save-memory` first. Record at minimum: the rule D exception and why it is narrow, and that three call sites mint keys (the `clone()` one is easy to miss).

- [ ] **Step 4: Open the PR**

Use the `create-pr` skill. Base `develop`. Note in the PR body that the branch also carries the earlier `docs/main-flows.md` commits.

---

## Self-Review

**Spec coverage:** Every spec section maps to a task — data model and "active" predicate → Task 1; backfill, drop, filter, principal, `last_used_at`, rate limiting, and all three minting call sites → Task 2; the four actions and `WINDOW_EXEMPT` → Task 3; API surface, error table, auditing → Task 4; rotation and the legacy 409 → Task 5; the documentation list → Task 6; the security review → Task 7.

**Two spec details deliberately changed during planning, both noted inline where they occur:**
1. The spec assigned migration number `019` to all three steps. The plan splits them across `019` (create table) and `020` (backfill + drop) so Task 1 can land green on its own; a single changeset file would have forced Tasks 1 and 2 to merge.
2. The spec called for a `created_by` FK with `ON DELETE SET NULL`. Liquibase cannot express that inline in `createTable`, and a deleted user must not take a live credential down, so the column carries no FK — the same reasoning the audit log already uses for having none.

**Known gap:** the backfill's correctness on PostgreSQL with real rows is verified manually (Task 2, Step 12), not by an automated test. The automated test covers the SQL on H2 with one seeded row, which catches a wrong column mapping but not an engine difference. This is called out rather than papered over because it is the one step that, done wrong, logs out every SDK client in production.
