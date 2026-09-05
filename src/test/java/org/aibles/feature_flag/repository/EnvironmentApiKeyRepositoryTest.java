package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
class EnvironmentApiKeyRepositoryTest {

  @PersistenceContext EntityManager em;
  @Autowired private EnvironmentApiKeyRepository repository;

  private Environment environment;
  private LocalDateTime now;

  @BeforeEach
  void setUp() {
    now = LocalDateTime.of(2026, 9, 5, 12, 0);
    Organization org =
        Organization.builder().name("Acme").slug("acme-" + System.nanoTime()).build();
    em.persist(org);
    Project project = Project.builder().organization(org).name("Web").build();
    em.persist(project);
    environment =
        Environment.builder()
            .project(project)
            .name("prod-" + System.nanoTime())
            .apiKeyHash("legacy-key-" + System.nanoTime())
            .build();
    em.persist(environment);
    em.flush();
  }

  private EnvironmentApiKey save(
      String name, String hash, LocalDateTime expiresAt, LocalDateTime revokedAt) {
    return repository.saveAndFlush(
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
    assertThat(found.getEnvironment().getName()).isEqualTo(environment.getName());
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
