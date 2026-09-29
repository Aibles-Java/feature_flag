package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@Transactional
class RefreshTokenRepositoryTest {

  @PersistenceContext EntityManager em;
  @Autowired RefreshTokenRepository repository;

  private UUID persistUser() {
    User user =
        User.builder().email("rt-" + System.nanoTime() + "@example.com").passwordHash("h").build();
    em.persist(user);
    em.flush();
    return user.getId();
  }

  /** 64-char lowercase hex, matching the SHA-256 hash shape the column is sized for. */
  private static String hash() {
    return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
  }

  private RefreshToken persistToken(UUID userId, UUID familyId) {
    return repository.saveAndFlush(
        RefreshToken.builder()
            .userId(userId)
            .familyId(familyId)
            .tokenHash(hash())
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
    // Second consume is a no-op — the WHERE rotated_at IS NULL no longer matches. This is the
    // atomicity guarantee reuse detection depends on: two concurrent refreshes, one winner.
    assertThat(repository.consume(saved.getId(), now)).isEqualTo(0);
  }

  @Test
  void revokeFamilyRevokesAllUnrevokedRowsInFamily() {
    UUID userId = persistUser();
    UUID family = UUID.randomUUID();
    persistToken(userId, family);
    persistToken(userId, family);
    persistToken(userId, UUID.randomUUID()); // other family, must be untouched

    assertThat(repository.revokeFamily(family, LocalDateTime.now())).isEqualTo(2);
    // Re-revoking is a no-op — revoked_at IS NULL no longer matches.
    assertThat(repository.revokeFamily(family, LocalDateTime.now())).isZero();
  }

  @Test
  void deleteByExpiresAtBeforeRemovesOnlyExpiredRows() {
    UUID userId = persistUser();
    UUID family = UUID.randomUUID();
    persistToken(userId, family); // expires in 14 days
    repository.saveAndFlush(
        RefreshToken.builder()
            .userId(userId)
            .familyId(family)
            .tokenHash(hash())
            .expiresAt(LocalDateTime.now().minusDays(1))
            .build());

    assertThat(repository.deleteByExpiresAtBefore(LocalDateTime.now())).isEqualTo(1);
  }
}
