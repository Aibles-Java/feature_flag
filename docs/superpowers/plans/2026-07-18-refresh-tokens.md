# Refresh Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 24h JWT with short-lived access tokens plus rotating, revocable refresh tokens that detect reuse.

**Architecture:** A `refresh_token` table stores one hashed opaque token per rotation step, grouped by `family_id` (one login/device). `RefreshTokenService` owns generation, hashing, rotation and reuse-detection; `AuthService` mints the access JWT and delegates refresh-token lifecycle to it. Access-token TTL and refresh-token TTL are separate configurable properties.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Liquibase, JJWT, Lombok, JUnit 5 + H2 (tests), AssertJ, Mockito.

## Global Constraints

- Spotless / google-java-format is CI-gating — run `./mvnw spotless:apply` before every commit.
- Migrations are immutable: add a **new** changeset (010), never edit 001–009. Wire it into `db.changelog-master.xml`.
- `refresh_token` migration number `010` collides with the unmerged audit-log PR #58; whichever merges second renumbers. Leave `010` here.
- Timestamps are `LocalDateTime` mapped to `TIMESTAMPTZ` (codebase-wide pattern).
- Opaque token = `SecureRandom` → 32 bytes → 64-char hex (256-bit). At rest = unsalted SHA-256 hex. The plaintext token is returned to the caller once and never stored.
- Authorization/permission logic stays out of controllers.
- TDD: write the failing test first, watch it fail, implement minimally, watch it pass, commit.

---

### Task 1: Split JWT config into access + refresh TTLs

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/config/JwtProperties.java`
- Modify: `src/main/java/org/aibles/feature_flag/security/JwtTokenProvider.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application-test.properties`
- Test: `src/test/java/org/aibles/feature_flag/security/JwtTokenProviderTest.java`
- Test: `src/test/java/org/aibles/feature_flag/config/JwtPropertiesValidationTest.java`

**Interfaces:**
- Produces: `JwtProperties(String secret, long accessExpirationMs, long refreshExpirationMs)` — record accessor names `secret()`, `accessExpirationMs()`, `refreshExpirationMs()`. `JwtTokenProvider` uses `accessExpirationMs()` for the JWT expiry.

- [ ] **Step 1: Update `JwtTokenProviderTest` constructions to the 3-arg record**

In `JwtTokenProviderTest.java`, every `new JwtProperties(SECRET, ONE_HOUR)` becomes `new JwtProperties(SECRET, ONE_HOUR, ONE_HOUR)`; `new JwtProperties(SECRET, -1_000L)` becomes `new JwtProperties(SECRET, -1_000L, 1_000L)`; and the 3-line `new JwtProperties(SECRET, ...)` at line 74 gains a third positive `long` argument (e.g. `, ONE_HOUR`). The provider only reads the access (2nd) arg, so the refresh (3rd) arg is any positive number.

- [ ] **Step 2: Run the provider test to verify it fails to compile**

Run: `./mvnw -o test -Dtest=JwtTokenProviderTest`
Expected: COMPILE FAIL — `JwtProperties` constructor still takes 2 args.

- [ ] **Step 3: Rewrite `JwtProperties` as a 3-field record**

Change the record header and add validation for the new field. Replace the record component list and add a `@Positive` refresh field:

```java
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
    @NotBlank(message = "app.jwt.secret is required — set the APP_JWT_SECRET environment variable")
        String secret,
    @Positive(message = "app.jwt.access-expiration-ms must be a positive number of milliseconds")
        long accessExpirationMs,
    @Positive(message = "app.jwt.refresh-expiration-ms must be a positive number of milliseconds")
        long refreshExpirationMs) {
```

Update the `toString()` override to not leak the secret and to print both TTLs:

```java
  @Override
  public String toString() {
    return "JwtProperties[secret=***, accessExpirationMs="
        + accessExpirationMs
        + ", refreshExpirationMs="
        + refreshExpirationMs
        + "]";
  }
```

Leave all the `@AssertTrue` secret-validation methods unchanged.

- [ ] **Step 4: Update `JwtTokenProvider` to use the access TTL**

In `JwtTokenProvider.java` constructor, change `this.expirationMs = properties.expirationMs();` to `this.expirationMs = properties.accessExpirationMs();`. The field name `expirationMs` may stay (it now holds the access TTL) — no other change needed.

- [ ] **Step 5: Update `application.properties`**

Replace the line `app.jwt.expiration-ms=86400000` with:

```properties
app.jwt.access-expiration-ms=900000
app.jwt.refresh-expiration-ms=1209600000
```

- [ ] **Step 6: Update `application-test.properties`**

Replace `app.jwt.expiration-ms=86400000` with:

```properties
app.jwt.access-expiration-ms=86400000
app.jwt.refresh-expiration-ms=1209600000
```

(Keep the access TTL long in tests so existing token-based tests are unaffected by TTL; only the config *shape* changes here.)

- [ ] **Step 7: Update `JwtPropertiesValidationTest`**

In every `.withPropertyValues(...)` call, replace `"app.jwt.expiration-ms=86400000"` with the two args `"app.jwt.access-expiration-ms=86400000", "app.jwt.refresh-expiration-ms=1209600000"`. Special cases:
- `missingSecretFailsStartup`: replace `"app.jwt.expiration-ms=86400000"` with both new args.
- `nonPositiveExpirationFailsStartup`: replace `"app.jwt.expiration-ms=0"` with `"app.jwt.access-expiration-ms=0", "app.jwt.refresh-expiration-ms=1209600000"` (keeps it a non-positive-access failure).
- `validSecretBindsAndStarts`: change the assertion `assertThat(props.expirationMs()).isEqualTo(86400000L);` to `assertThat(props.accessExpirationMs()).isEqualTo(86400000L);`.

- [ ] **Step 8: Run both tests to verify they pass**

Run: `./mvnw -o spotless:apply && ./mvnw -o test -Dtest=JwtTokenProviderTest,JwtPropertiesValidationTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/config/JwtProperties.java \
        src/main/java/org/aibles/feature_flag/security/JwtTokenProvider.java \
        src/main/resources/application.properties \
        src/test/resources/application-test.properties \
        src/test/java/org/aibles/feature_flag/security/JwtTokenProviderTest.java \
        src/test/java/org/aibles/feature_flag/config/JwtPropertiesValidationTest.java
git commit -m "feat(auth): split JWT config into access + refresh TTLs"
```

---

### Task 2: `RefreshToken` entity + migration 010

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/domain/entity/RefreshToken.java`
- Create: `src/main/resources/db/changelog/migrations/010-create-refresh-tokens.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.xml`

**Interfaces:**
- Produces: `RefreshToken` entity (`@Builder`) with fields `UUID id`, `UUID userId`, `UUID familyId`, `String tokenHash`, `LocalDateTime expiresAt`, `LocalDateTime rotatedAt`, `LocalDateTime revokedAt`, `LocalDateTime createdAt`. Table `refresh_token`.

- [ ] **Step 1: Create the entity**

```java
package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "rotated_at")
  private LocalDateTime rotatedAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
```

`userId` is a plain `UUID` column, not a `@ManyToOne` association — this deliberately avoids lazy-loading pitfalls; the FK is enforced at the DB level by the migration.

- [ ] **Step 2: Create migration 010**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="010-create-refresh-tokens" author="dev">
        <createTable tableName="refresh_token">
            <column name="id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="user_id" type="UUID">
                <constraints nullable="false"
                             foreignKeyName="fk_refresh_token_user"
                             references="users(id)"
                             deleteCascade="true"/>
            </column>
            <column name="family_id" type="UUID">
                <constraints nullable="false"/>
            </column>
            <column name="token_hash" type="VARCHAR(64)">
                <constraints nullable="false" unique="true"
                             uniqueConstraintName="uq_refresh_token_hash"/>
            </column>
            <column name="expires_at" type="TIMESTAMPTZ">
                <constraints nullable="false"/>
            </column>
            <column name="rotated_at" type="TIMESTAMPTZ"/>
            <column name="revoked_at" type="TIMESTAMPTZ"/>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="refresh_token" indexName="idx_refresh_token_family">
            <column name="family_id"/>
        </createIndex>
        <createIndex tableName="refresh_token" indexName="idx_refresh_token_user">
            <column name="user_id"/>
        </createIndex>
        <rollback>
            <dropTable tableName="refresh_token"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Wire it into the master changelog**

In `db.changelog-master.xml`, add after the `009-hash-api-keys.xml` include line:

```xml
    <include file="db/changelog/migrations/010-create-refresh-tokens.xml"/>
```

- [ ] **Step 4: Verify the schema applies on H2**

Run: `./mvnw -o test -Dtest=SecurityChainIntegrationTest`
Expected: PASS — the app context boots, so Liquibase applied migration 010 cleanly against H2 (the `${json.type}`-free DDL and `gen_random_uuid()` default follow the existing 001–009 pattern that H2 already tolerates).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/domain/entity/RefreshToken.java \
        src/main/resources/db/changelog/migrations/010-create-refresh-tokens.xml \
        src/main/resources/db/changelog/db.changelog-master.xml
git commit -m "feat(auth): add refresh_token table + entity (migration 010)"
```

---

### Task 3: `RefreshTokenRepository`

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/repository/RefreshTokenRepository.java`
- Test: `src/test/java/org/aibles/feature_flag/repository/RefreshTokenRepositoryTest.java`

**Interfaces:**
- Consumes: `RefreshToken` entity (Task 2).
- Produces: `RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>` with `Optional<RefreshToken> findByTokenHash(String)`, `int consume(UUID id, LocalDateTime now)` (conditional UPDATE, returns affected rows), `int revokeFamily(UUID familyId, LocalDateTime now)`, `int deleteByExpiresAtBefore(LocalDateTime cutoff)`.

- [ ] **Step 1: Write the failing repository test**

```java
package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RefreshTokenRepositoryTest {

  @Autowired private RefreshTokenRepository repository;
  @Autowired private UserRepository userRepository;

  private UUID persistUser() {
    User user =
        User.builder().email(UUID.randomUUID() + "@ex.com").passwordHash("x").build();
    return userRepository.saveAndFlush(user).getId();
  }

  private RefreshToken persistToken(UUID userId, UUID familyId) {
    return repository.saveAndFlush(
        RefreshToken.builder()
            .userId(userId)
            .familyId(familyId)
            .tokenHash(UUID.randomUUID().toString().replace("-", "") + "0000000000000000000000000000")
            .expiresAt(LocalDateTime.now().plusDays(14))
            .build());
  }

  @Test
  void findByTokenHashReturnsRow() {
    UUID userId = persistUser();
    RefreshToken saved = persistToken(userId, UUID.randomUUID());
    assertThat(repository.findByTokenHash(saved.getTokenHash())).isPresent();
    assertThat(repository.findByTokenHash("nope")).isEmpty();
  }

  @Test
  void consumeSetsRotatedAtExactlyOnce() {
    UUID userId = persistUser();
    RefreshToken saved = persistToken(userId, UUID.randomUUID());
    LocalDateTime now = LocalDateTime.now();

    assertThat(repository.consume(saved.getId(), now)).isEqualTo(1);
    // second consume is a no-op — the WHERE rotated_at IS NULL no longer matches
    assertThat(repository.consume(saved.getId(), now)).isEqualTo(0);
  }

  @Test
  void revokeFamilyRevokesAllUnrevokedRowsInFamily() {
    UUID userId = persistUser();
    UUID family = UUID.randomUUID();
    persistToken(userId, family);
    persistToken(userId, family);
    persistToken(userId, UUID.randomUUID()); // other family, untouched

    int revoked = repository.revokeFamily(family, LocalDateTime.now());
    assertThat(revoked).isEqualTo(2);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -o test -Dtest=RefreshTokenRepositoryTest`
Expected: COMPILE FAIL — `RefreshTokenRepository` does not exist yet.

- [ ] **Step 3: Create the repository**

```java
package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.rotatedAt = :now WHERE r.id = :id AND r.rotatedAt IS NULL")
  int consume(@Param("id") UUID id, @Param("now") LocalDateTime now);

  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.revokedAt = :now "
          + "WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);

  int deleteByExpiresAtBefore(LocalDateTime cutoff);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -o spotless:apply && ./mvnw -o test -Dtest=RefreshTokenRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/repository/RefreshTokenRepository.java \
        src/test/java/org/aibles/feature_flag/repository/RefreshTokenRepositoryTest.java
git commit -m "feat(auth): RefreshTokenRepository with atomic consume + family revoke"
```

---

### Task 4: `RefreshTokenService` — issue / rotate / logout with reuse detection

**Files:**
- Create: `src/main/java/org/aibles/feature_flag/service/RefreshTokenService.java`
- Create: `src/main/java/org/aibles/feature_flag/service/impl/RefreshTokenServiceImpl.java`
- Test: `src/test/java/org/aibles/feature_flag/service/impl/RefreshTokenServiceImplTest.java`

**Interfaces:**
- Consumes: `RefreshTokenRepository` (Task 3), `UserRepository`, `JwtProperties` (Task 1, `refreshExpirationMs()`).
- Produces:
  - `String issueNewFamily(UUID userId)` — creates a new family, returns the plaintext token.
  - `RotationResult rotate(String presentedToken)` — validates + reuse-detects + rotates; throws `UnauthorizedException` on any invalid case. `RotationResult` is `record RotationResult(UUID userId, String refreshToken)`.
  - `void logout(String presentedToken)` — idempotent family revoke; never throws.
  - `static String hash(String token)` on the impl (SHA-256 hex) — used by tests to seed rows.

- [ ] **Step 1: Write the failing service test**

```java
package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.RefreshTokenService.RotationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

  @Mock private RefreshTokenRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private JwtProperties jwtProperties;
  @InjectMocks private RefreshTokenServiceImpl service;

  private final UUID userId = UUID.randomUUID();
  private final UUID familyId = UUID.randomUUID();

  @BeforeEach
  void stubTtl() {
    when(jwtProperties.refreshExpirationMs()).thenReturn(1_209_600_000L);
  }

  private RefreshToken activeRow(String plaintext) {
    return RefreshToken.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .familyId(familyId)
        .tokenHash(RefreshTokenServiceImpl.hash(plaintext))
        .expiresAt(LocalDateTime.now().plusDays(14))
        .build();
  }

  @Test
  void issueNewFamilyPersistsRowAndReturnsPlaintext() {
    String token = service.issueNewFamily(userId);
    assertThat(token).hasSize(64);
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getTokenHash())
        .isEqualTo(RefreshTokenServiceImpl.hash(token));
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
  }

  @Test
  void rotateHappyPathConsumesAndIssuesNewToken() {
    String presented = "a".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(repository.consume(eq(row.getId()), any())).thenReturn(1);
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(true).build()));

    RotationResult result = service.rotate(presented);

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.refreshToken()).hasSize(64).isNotEqualTo(presented);
    verify(repository).consume(eq(row.getId()), any());
  }

  @Test
  void rotateOfAlreadyRotatedTokenRevokesFamily() {
    String presented = "b".repeat(64);
    RefreshToken row = activeRow(presented);
    row.setRotatedAt(LocalDateTime.now().minusMinutes(1));
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(UnauthorizedException.class);
    verify(repository).revokeFamily(eq(familyId), any());
  }

  @Test
  void rotateLosingTheConcurrentConsumeRevokesFamily() {
    String presented = "c".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(repository.consume(eq(row.getId()), any())).thenReturn(0); // someone else won

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(UnauthorizedException.class);
    verify(repository).revokeFamily(eq(familyId), any());
  }

  @Test
  void rotateOfExpiredTokenIsRejected() {
    String presented = "d".repeat(64);
    RefreshToken row = activeRow(presented);
    row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void rotateForDisabledUserRevokesFamily() {
    String presented = "e".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(repository.consume(eq(row.getId()), any())).thenReturn(1);
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(false).build()));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(UnauthorizedException.class);
    verify(repository).revokeFamily(eq(familyId), any());
  }

  @Test
  void rotateOfUnknownTokenIsRejected() {
    when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.rotate("f".repeat(64)))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void logoutRevokesFamilyWhenTokenKnown() {
    String presented = "g".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    service.logout(presented);
    verify(repository).revokeFamily(eq(familyId), any());
  }

  @Test
  void logoutOfUnknownTokenIsSilent() {
    when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
    service.logout("h".repeat(64)); // no throw, no revoke
  }
}
```

> Note: `stubTtl()` uses a plain (non-lenient) stub. If any test does not reach `createRow` (e.g. the unknown-token / already-rotated / expired paths), Mockito will flag `refreshExpirationMs()` as unnecessary. To keep it simple, mark the stub lenient: annotate the class with `@MockitoSettings(strictness = Strictness.LENIENT)` (import `org.mockito.junit.jupiter.MockitoSettings` and `org.mockito.quality.Strictness`).

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -o test -Dtest=RefreshTokenServiceImplTest`
Expected: COMPILE FAIL — `RefreshTokenService` / impl do not exist.

- [ ] **Step 3: Create the service interface**

```java
package org.aibles.feature_flag.service;

import java.util.UUID;

public interface RefreshTokenService {

  /** Starts a new rotation family for the user; returns the plaintext refresh token. */
  String issueNewFamily(UUID userId);

  /** Validates + rotates a presented token. Throws UnauthorizedException on any invalid case. */
  RotationResult rotate(String presentedToken);

  /** Revokes the family of the presented token. Idempotent; never throws on unknown token. */
  void logout(String presentedToken);

  record RotationResult(UUID userId, String refreshToken) {}
}
```

- [ ] **Step 4: Create the implementation**

```java
package org.aibles.feature_flag.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.RefreshTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final RefreshTokenRepository repository;
  private final UserRepository userRepository;
  private final JwtProperties jwtProperties;

  @Override
  @Transactional
  public String issueNewFamily(UUID userId) {
    return createRow(userId, UUID.randomUUID());
  }

  @Override
  @Transactional
  public RotationResult rotate(String presentedToken) {
    RefreshToken row =
        repository
            .findByTokenHash(hash(presentedToken))
            .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

    LocalDateTime now = LocalDateTime.now();

    if (row.getRevokedAt() != null) {
      throw new UnauthorizedException("Refresh token has been revoked");
    }
    if (row.getExpiresAt().isBefore(now)) {
      throw new UnauthorizedException("Refresh token has expired");
    }
    if (row.getRotatedAt() != null) {
      // A consumed token was replayed — the family is compromised.
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Refresh token reuse detected");
    }

    // Atomically consume. A concurrent request that already consumed it wins; we lose and
    // treat the loss as a reuse signal, revoking the family.
    if (repository.consume(row.getId(), now) != 1) {
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Refresh token reuse detected");
    }

    User user =
        userRepository
            .findById(row.getUserId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
    if (!user.isEnabled()) {
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Account is disabled");
    }

    String newToken = createRow(row.getUserId(), row.getFamilyId());
    return new RotationResult(row.getUserId(), newToken);
  }

  @Override
  @Transactional
  public void logout(String presentedToken) {
    repository
        .findByTokenHash(hash(presentedToken))
        .ifPresent(row -> repository.revokeFamily(row.getFamilyId(), LocalDateTime.now()));
  }

  private String createRow(UUID userId, UUID familyId) {
    String token = generateToken();
    RefreshToken row =
        RefreshToken.builder()
            .userId(userId)
            .familyId(familyId)
            .tokenHash(hash(token))
            .expiresAt(LocalDateTime.now().plus(jwtProperties.refreshExpirationMs(), ChronoUnit.MILLIS))
            .build();
    repository.save(row);
    return token;
  }

  private static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes); // 64-char hex, 256-bit
  }

  /** Unsalted SHA-256 hex — sufficient for a 256-bit random token, keeps lookup O(1). */
  static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -o spotless:apply && ./mvnw -o test -Dtest=RefreshTokenServiceImplTest`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/service/RefreshTokenService.java \
        src/main/java/org/aibles/feature_flag/service/impl/RefreshTokenServiceImpl.java \
        src/test/java/org/aibles/feature_flag/service/impl/RefreshTokenServiceImplTest.java
git commit -m "feat(auth): RefreshTokenService with rotation + reuse detection"
```

---

### Task 5: Auth DTOs + `AuthService` login/refresh/logout

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/dto/response/AuthResponse.java`
- Create: `src/main/java/org/aibles/feature_flag/dto/request/RefreshRequest.java`
- Create: `src/main/java/org/aibles/feature_flag/dto/request/LogoutRequest.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/AuthService.java`
- Modify: `src/main/java/org/aibles/feature_flag/service/impl/AuthServiceImpl.java`
- Test: `src/test/java/org/aibles/feature_flag/service/impl/AuthServiceImplTest.java`

**Interfaces:**
- Consumes: `RefreshTokenService` (Task 4), `JwtProperties.accessExpirationMs()` (Task 1), `JwtTokenProvider.generateToken(UserPrincipal)`, `UserRepository`, `UserPrincipal.from(User)`.
- Produces: `AuthResponse { String accessToken; String refreshToken; String tokenType="Bearer"; long expiresIn; UUID userId; String email; }`; `AuthService.refresh(RefreshRequest)` → `AuthResponse`; `AuthService.logout(LogoutRequest)` → void.

- [ ] **Step 1: Rewrite `AuthResponse`**

```java
package org.aibles.feature_flag.dto.response;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
  private String accessToken;
  private String refreshToken;
  @Builder.Default private String tokenType = "Bearer";
  private long expiresIn; // access-token lifetime in seconds
  private UUID userId;
  private String email;
}
```

- [ ] **Step 2: Create `RefreshRequest` and `LogoutRequest`**

```java
package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {
  @NotBlank private String refreshToken;
}
```

```java
package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LogoutRequest {
  @NotBlank private String refreshToken;
}
```

- [ ] **Step 3: Extend the `AuthService` interface**

```java
package org.aibles.feature_flag.service;

import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.LogoutRequest;
import org.aibles.feature_flag.dto.request.RefreshRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;

public interface AuthService {
  void register(RegisterRequest request);

  AuthResponse login(LoginRequest request);

  AuthResponse refresh(RefreshRequest request);

  void logout(LogoutRequest request);
}
```

- [ ] **Step 4: Update `AuthServiceImplTest` for the new shape (write the failing test)**

Replace the whole test class body's login assertion and add refresh/logout tests. The class already mocks `UserRepository`, `PasswordEncoder`, `AuthenticationManager`, `JwtTokenProvider`; add `@Mock RefreshTokenService refreshTokenService;` and `@Mock JwtProperties jwtProperties;`. Key assertions:

```java
// in the existing login test, after building the request and stubbing auth:
when(jwtTokenProvider.generateToken(principal)).thenReturn("jwt-token");
when(refreshTokenService.issueNewFamily(principal.getId())).thenReturn("refresh-token");
when(jwtProperties.accessExpirationMs()).thenReturn(900_000L);

AuthResponse response = authService.login(req);

assertThat(response.getAccessToken()).isEqualTo("jwt-token");
assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
assertThat(response.getExpiresIn()).isEqualTo(900L);
```

Add a refresh test:

```java
@Test
void refreshRotatesAndMintsNewAccessToken() {
  UUID userId = UUID.randomUUID();
  User user = User.builder().id(userId).email("a@ex.com").passwordHash("x").build();
  when(refreshTokenService.rotate("old-refresh"))
      .thenReturn(new RefreshTokenService.RotationResult(userId, "new-refresh"));
  when(userRepository.findById(userId)).thenReturn(Optional.of(user));
  when(jwtTokenProvider.generateToken(any())).thenReturn("new-jwt");
  when(jwtProperties.accessExpirationMs()).thenReturn(900_000L);

  RefreshRequest req = new RefreshRequest();
  req.setRefreshToken("old-refresh");
  AuthResponse res = authService.refresh(req);

  assertThat(res.getAccessToken()).isEqualTo("new-jwt");
  assertThat(res.getRefreshToken()).isEqualTo("new-refresh");
  assertThat(res.getUserId()).isEqualTo(userId);
}

@Test
void logoutDelegatesToRefreshTokenService() {
  LogoutRequest req = new LogoutRequest();
  req.setRefreshToken("some-refresh");
  authService.logout(req);
  verify(refreshTokenService).logout("some-refresh");
}
```

Add the needed imports (`Optional`, `UUID`, `any`, `verify`, `RefreshTokenService`, `RefreshRequest`, `LogoutRequest`, `User`).

- [ ] **Step 5: Run to verify it fails**

Run: `./mvnw -o test -Dtest=AuthServiceImplTest`
Expected: COMPILE FAIL — `refresh`/`logout` and new fields don't exist yet.

- [ ] **Step 6: Update `AuthServiceImpl`**

Add fields and methods. New constructor deps: `RefreshTokenService refreshTokenService`, `JwtProperties jwtProperties` (both via the existing `@RequiredArgsConstructor`).

```java
  private final RefreshTokenService refreshTokenService;
  private final JwtProperties jwtProperties;

  @Override
  @Transactional
  public AuthResponse login(LoginRequest request) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
    UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

    String accessToken = jwtTokenProvider.generateToken(principal);
    String refreshToken = refreshTokenService.issueNewFamily(principal.getId());

    return AuthResponse.builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .expiresIn(jwtProperties.accessExpirationMs() / 1000)
        .userId(principal.getId())
        .email(principal.getEmail())
        .build();
  }

  @Override
  @Transactional
  public AuthResponse refresh(RefreshRequest request) {
    RefreshTokenService.RotationResult rotation =
        refreshTokenService.rotate(request.getRefreshToken());
    User user =
        userRepository
            .findById(rotation.userId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
    UserPrincipal principal = UserPrincipal.from(user);

    return AuthResponse.builder()
        .accessToken(jwtTokenProvider.generateToken(principal))
        .refreshToken(rotation.refreshToken())
        .expiresIn(jwtProperties.accessExpirationMs() / 1000)
        .userId(user.getId())
        .email(user.getEmail())
        .build();
  }

  @Override
  @Transactional
  public void logout(LogoutRequest request) {
    refreshTokenService.logout(request.getRefreshToken());
  }
```

Add imports: `org.aibles.feature_flag.dto.request.LogoutRequest`, `org.aibles.feature_flag.dto.request.RefreshRequest`, `org.aibles.feature_flag.config.JwtProperties`, `org.aibles.feature_flag.exception.UnauthorizedException`, `org.aibles.feature_flag.service.RefreshTokenService`. Add `@Transactional` import if not present (it is).

- [ ] **Step 7: Run to verify it passes**

Run: `./mvnw -o spotless:apply && ./mvnw -o test -Dtest=AuthServiceImplTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/dto/response/AuthResponse.java \
        src/main/java/org/aibles/feature_flag/dto/request/RefreshRequest.java \
        src/main/java/org/aibles/feature_flag/dto/request/LogoutRequest.java \
        src/main/java/org/aibles/feature_flag/service/AuthService.java \
        src/main/java/org/aibles/feature_flag/service/impl/AuthServiceImpl.java \
        src/test/java/org/aibles/feature_flag/service/impl/AuthServiceImplTest.java
git commit -m "feat(auth): issue refresh token on login, add refresh + logout service paths"
```

---

### Task 6: `AuthController` endpoints + integration test

**Files:**
- Modify: `src/main/java/org/aibles/feature_flag/controller/auth/AuthController.java`
- Test: `src/test/java/org/aibles/feature_flag/controller/auth/AuthControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `AuthService.refresh(RefreshRequest)`, `AuthService.logout(LogoutRequest)`.
- Produces: `POST /api/v1/auth/refresh` → `200` + `AuthResponse`; `POST /api/v1/auth/logout` → `204`.

- [ ] **Step 1: Write the failing integration test**

Full end-to-end against the real security chain + H2. Registers, logs in, refreshes, then proves the old token is dead (reuse revokes the family).

```java
package org.aibles.feature_flag.controller.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private String register(String email) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            java.util.Map.of(
                "email", email, "password", "Password123!", "firstName", "A", "lastName", "B"));
    mockMvc
        .perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    String login =
        objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", "Password123!"));
    return mockMvc
        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String field(String json, String name) throws Exception {
    return objectMapper.readTree(json).get(name).asText();
  }

  @Test
  void refreshRotatesAndReuseRevokesFamily() throws Exception {
    String loginJson = register("rotate@ex.com");
    String oldRefresh = field(loginJson, "refreshToken");

    String refreshBody = objectMapper.writeValueAsString(java.util.Map.of("refreshToken", oldRefresh));
    String refreshedJson =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // reuse the now-rotated old token → 401 (and family revoked)
    mockMvc
        .perform(
            post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody))
        .andExpect(status().isUnauthorized());

    // the freshly issued token is now also dead because the family was revoked
    String newRefresh = field(refreshedJson, "refreshToken");
    String newBody = objectMapper.writeValueAsString(java.util.Map.of("refreshToken", newRefresh));
    mockMvc
        .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(newBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesFamilyAndIsIdempotent() throws Exception {
    String loginJson = register("logout@ex.com");
    String refresh = field(loginJson, "refreshToken");
    String body = objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refresh));

    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());

    // refreshing after logout fails
    mockMvc
        .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());

    // logout again is still 204 (idempotent, no enumeration)
    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());
  }
}
```

> If `UnauthorizedException` is not already mapped to HTTP 401 by the global exception handler, this test's `isUnauthorized()` will fail — see Step 4.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -o test -Dtest=AuthControllerIntegrationTest`
Expected: FAIL — `/refresh` and `/logout` return 404 (endpoints don't exist).

- [ ] **Step 3: Add the controller endpoints**

```java
  @PostMapping("/refresh")
  public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request);
  }
```

Add imports: `org.aibles.feature_flag.dto.request.RefreshRequest`, `org.aibles.feature_flag.dto.request.LogoutRequest`.

- [ ] **Step 4: Ensure `UnauthorizedException` → 401**

Check `src/main/java/org/aibles/feature_flag/exception/` for the global handler (e.g. `GlobalExceptionHandler` / `@RestControllerAdvice`). Confirm `UnauthorizedException` maps to `HttpStatus.UNAUTHORIZED`. If it already does (it is used by existing 401 paths), no change. If not, add a `@ExceptionHandler(UnauthorizedException.class)` returning 401. Verify by reading the handler before assuming.

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -o spotless:apply && ./mvnw -o test -Dtest=AuthControllerIntegrationTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/aibles/feature_flag/controller/auth/AuthController.java \
        src/test/java/org/aibles/feature_flag/controller/auth/AuthControllerIntegrationTest.java
git commit -m "feat(auth): POST /auth/refresh + /auth/logout endpoints"
```

---

### Task 7: Config docs, prod props, security review, full verify

**Files:**
- Modify: `README.md` (env-var table)
- Modify: `src/main/resources/application-prod.properties` (if it needs the new keys)
- Verify: `SecurityConfig` auth matcher covers `/auth/**`; check rate-limit coverage.

- [ ] **Step 1: Confirm the security chain covers the new paths**

Read `src/main/java/org/aibles/feature_flag/config/SecurityConfig.java`. Confirm the admin/public matcher permits `/api/v1/auth/**` (login/register are already public, so `/refresh` + `/logout` are covered). If the config lists explicit auth sub-paths rather than `/auth/**`, add `/api/v1/auth/refresh` and `/api/v1/auth/logout` to the permit-all list. Also check whether `security/ratelimit` wraps `/auth/login`; if it does not, note it in the PR (do not add rate limiting — out of scope).

- [ ] **Step 2: Update `application-prod.properties`**

`app.jwt.access-expiration-ms` / `app.jwt.refresh-expiration-ms` inherit from `application.properties` unless prod must override. If prod should pin them via env, add:

```properties
app.jwt.access-expiration-ms=${APP_JWT_ACCESS_EXPIRATION_MS}
app.jwt.refresh-expiration-ms=${APP_JWT_REFRESH_EXPIRATION_MS}
```

Only add these if the prod profile is meant to require them explicitly (matches the "no defaults in prod" convention for secrets — TTLs are not secrets, so inheriting the base values is acceptable; prefer the simpler no-override unless the team wants env control). Decide and document the choice in the PR body.

- [ ] **Step 3: Update `README.md` env-var table**

Replace the `APP_JWT_EXPIRATION_MS` row with two rows:

```
| APP_JWT_ACCESS_EXPIRATION_MS  | access-token lifetime in ms (default 900000 = 15 min)     |
| APP_JWT_REFRESH_EXPIRATION_MS | refresh-token lifetime in ms (default 1209600000 = 14 d)  |
```

Add a one-line note that `POST /api/v1/auth/refresh` rotates the refresh token and `POST /api/v1/auth/logout` revokes the device's token family. Note the **ops migration**: `APP_JWT_EXPIRATION_MS` is no longer read.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw -o spotless:apply && ./mvnw -o verify`
Expected: BUILD SUCCESS — all tests pass, Spotless clean, JaCoCo floor met.

- [ ] **Step 5: Security review**

Run the `security-review` skill (touches auth flow + JWT config + token handling). Address CRITICAL/HIGH findings. Key checks: no plaintext refresh token logged or persisted; hash used everywhere for lookup; reuse-detection revokes the whole family; access-token TTL actually shortened; no secret in `JwtProperties.toString()`.

- [ ] **Step 6: Commit**

```bash
git add README.md src/main/resources/application-prod.properties
git commit -m "docs(auth): document refresh-token endpoints + split JWT TTL env vars"
```

---

## Self-Review

**Spec coverage:**
- Refresh token entity (opaque, hashed, per-family, ~14d) + rotation with reuse detection → Tasks 2, 3, 4. ✓
- `POST /auth/refresh` + `POST /auth/logout` (revokes family) → Tasks 5, 6. ✓
- Access TTL → ~15min configurable → Task 1. ✓
- Liquibase changeset → Task 2. ✓
- Existing tests updated → Tasks 1 (JWT tests), 5 (AuthServiceImplTest). ✓
- Security review → Task 7. ✓
- Concurrency (atomic consume) → Task 4 (`consume` returns affected rows). ✓
- FK cascade on user delete → Task 2 migration. ✓
- `user.enabled` check on refresh → Task 4. ✓
- Known limitation (access token survives logout until expiry) → documented in spec; nothing to implement. ✓

**Placeholder scan:** No TBD/TODO; every code step has full code. Task 6 Step 4 and Task 7 Steps 1–2 require *reading* an existing file (global exception handler, SecurityConfig, prod props) before deciding a small edit — these are genuine verification steps, not placeholders, and each states exactly what to look for and the fallback edit.

**Type consistency:** `RotationResult(UUID userId, String refreshToken)` used identically in Task 4 (produce), Task 5 (consume). `JwtProperties.accessExpirationMs()`/`refreshExpirationMs()` consistent across Tasks 1, 4, 5. `RefreshTokenServiceImpl.hash(...)` (static) used in the Task 4 test and impl. `AuthResponse` getters (`getAccessToken`, `getRefreshToken`, `getExpiresIn`) consistent Tasks 5, 6.
